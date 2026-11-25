package com.example.screenrecorder

import android.app.Activity
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Owns one screen-capture session end to end:
 *
 * ```
 * consent token -> MediaProjection -> VirtualDisplay -> MediaRecorder surface
 *                                                                 |
 *                                                                 v
 *                                             MediaStore -> Movies/ScreenRecorder/REC_*.mp4
 * ```
 *
 * Ordering rules this class must respect (violating any of them crashes on Android 14):
 *
 * 1. `startForeground()` with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` **before** the
 *    projection is used, otherwise the system throws
 *    `SecurityException: Starting FGS with type mediaProjection ... requires ...`.
 * 2. `MediaProjection.registerCallback()` **before** `createVirtualDisplay()`, otherwise
 *    `IllegalStateException: MediaProjection requires a Callback to be registered before it
 *    can be used`.
 * 3. The [ParcelFileDescriptor] handed to MediaRecorder stays open until after
 *    `MediaRecorder.stop()` returns - closing it early truncates the moov atom and the
 *    resulting .mp4 is unplayable.
 * 4. `MediaRecorder.stop()` blocks while it flushes the encoder, so finalisation runs on a
 *    worker thread and only UI/notification work returns to the main looper.
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"

        /** MediaRecorder hard-stops here so a forgotten session cannot fill the disk. */
        private const val MAX_DURATION_MS = 3 * 60 * 60 * 1000

        private const val FILE_PREFIX = "REC_"
    }

    /** Main-thread callback used by [MainActivity] to mirror the session state. */
    fun interface OnStateChange {
        fun onStateChanged(state: String, message: String?)
    }

    inner class LocalBinder : Binder() {
        val service: ScreenRecordService get() = this@ScreenRecordService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finalizeExecutor = Executors.newSingleThreadExecutor()
    private val observers = CopyOnWriteArraySet<OnStateChange>()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var sessionConfig: RecorderConfig? = null

    private var startedAtRealtime = 0L
    private var frozenElapsedMillis = 0L
    private var foregroundStarted = false

    var state: String = RecordingContract.STATE_IDLE
        private set

    /** Elapsed time of the current (or just finished) session; safe to poll from the UI. */
    val elapsedMillis: Long
        get() = if (state == RecordingContract.STATE_RECORDING) {
            SystemClock.elapsedRealtime() - startedAtRealtime
        } else {
            frozenElapsedMillis
        }

    val isBusy: Boolean
        get() = state == RecordingContract.STATE_RECORDING ||
            state == RecordingContract.STATE_PREPARING ||
            state == RecordingContract.STATE_FINALIZING

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Fires when the user taps the system cast/recording tile or revokes access.
            // Handle it like an in-app stop so the file is still muxed and saved.
            Log.i(TAG, "MediaProjection.onStop() -> finalising the current take")
            if (state == RecordingContract.STATE_RECORDING ||
                state == RecordingContract.STATE_PREPARING
            ) {
                stopRecording(getString(R.string.notif_projection_revoked))
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecordingContract.ACTION_START -> handleStart(intent)
            RecordingContract.ACTION_STOP -> stopRecording(null)
            else -> Log.w(TAG, "Ignoring unexpected start action: ${intent?.action}")
        }
        // A restarted service holds no consent token and can never resume a session, so
        // START_NOT_STICKY is the honest answer: no zombie foreground service.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (isBusy) stopRecording(reason = null, synchronous = true)
        observers.clear()
        runCatching { finalizeExecutor.shutdown() }
        super.onDestroy()
    }

    // ------------------------------------------------------------- start

    private fun handleStart(intent: Intent) {
        if (isBusy) {
            Log.w(TAG, "Start ignored, service is already in state=$state")
            return
        }

        val resultCode = intent.getIntExtra(
            RecordingContract.EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )
        val resultData = intent.captureDataExtra()
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            fail(getString(R.string.error_consent_missing))
            return
        }

        val config = RecorderConfig(
            width = intent.getIntExtra(RecordingContract.EXTRA_WIDTH, 720),
            height = intent.getIntExtra(RecordingContract.EXTRA_HEIGHT, 1280),
            densityDpi = intent.getIntExtra(RecordingContract.EXTRA_DENSITY, 320),
            frameRate = intent.getIntExtra(RecordingContract.EXTRA_FRAME_RATE, 30),
            videoBitrate = intent.getIntExtra(RecordingContract.EXTRA_VIDEO_BITRATE, 8_000_000),
            microphone = intent.getBooleanExtra(RecordingContract.EXTRA_MICROPHONE, false)
        )
        beginRecording(resultCode, resultData, config)
    }

    private fun beginRecording(resultCode: Int, resultData: Intent, config: RecorderConfig) {
        sessionConfig = config
        frozenElapsedMillis = 0L
        transition(RecordingContract.STATE_PREPARING, null)

        var projection: MediaProjection? = null
        var recorder: MediaRecorder? = null
        var uri: Uri? = null
        try {
            // Rule 1: be a mediaProjection foreground service before touching the projection.
            // It sits inside the try so a refused startForeground (missing manifest type,
            // missing permission) degrades to an error state instead of crashing the process.
            enterForeground(RecordingContract.STATE_PREPARING, config)
            val manager = getSystemService(MediaProjectionManager::class.java)
            // The consent result data is single-use on Android 14 - it is exchanged here for a
            // projection and can never be replayed for a second silent recording.
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("MediaProjectionManager returned a null projection")

            // Rule 2: callback first, display second.
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            uri = createPendingOutput()
            outputUri = uri

            recorder = createRecorder(config, uri)
            mediaRecorder = recorder

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenRecorderCapture",
                config.width,
                config.height,
                config.densityDpi,
                // PUBLIC keeps the mirrored surface alive when the screen dims briefly.
                // Deliberately no OWN_CONTENT_ONLY: we want the whole screen, not just our app.
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                recorder.surface,
                null,
                mainHandler
            )

            recorder.start()
            startedAtRealtime = SystemClock.elapsedRealtime()

            enterForeground(RecordingContract.STATE_RECORDING, config)
            transition(RecordingContract.STATE_RECORDING, null)
            Log.i(TAG, "Started ${config.label} mic=${config.microphone} -> $uri")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start the recording session", error)
            recorder?.let { runCatching { it.release() } }
            mediaRecorder = null
            virtualDisplay = null
            runCatching { recorder?.reset() }
            uri?.let { discardPendingOutput(it) }
            outputUri = null
            runCatching { outputDescriptor?.close() }
            outputDescriptor = null
            projection?.let { releaseProjection(it) }
            fail(getString(R.string.error_start_failed))
        }
    }

    @Throws(Exception::class)
    private fun createRecorder(config: RecorderConfig, uri: Uri): MediaRecorder {
        // The Context overload (S+) associates the recorder with this process, which measurably
        // reduces encoder starvation under memory pressure.
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }

        if (config.microphone) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        recorder.setVideoSize(config.width, config.height)
        recorder.setVideoFrameRate(config.frameRate)
        recorder.setVideoEncodingBitRate(config.videoBitrate)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        if (config.microphone) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(RecorderConfig.AUDIO_BITRATE)
            recorder.setAudioSamplingRate(RecorderConfig.AUDIO_SAMPLE_RATE)
            recorder.setAudioChannels(2)
        }

        recorder.setMaxDuration(MAX_DURATION_MS)
        recorder.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                Log.w(TAG, "Max duration reached, closing the session")
                stopRecording(getString(R.string.error_max_duration))
            }
        }
        recorder.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
            stopRecording(getString(R.string.error_encoder, what))
        }

        // Rule 3: the descriptor must outlive recorder.stop().
        val descriptor = contentResolver.openFileDescriptor(uri, "rw")
            ?: error("MediaStore refused to open $uri")
        outputDescriptor = descriptor
        recorder.setOutputFile(descriptor.fileDescriptor)

        recorder.prepare()
        return recorder
    }

    // ------------------------------------------------------------- stop

    fun stopRecording(reason: String?) {
        stopRecording(reason, synchronous = false)
    }

    private fun stopRecording(reason: String?, synchronous: Boolean) {
        if (!isBusy) {
            stopForegroundAndSelf()
            return
        }

        val recorder = mediaRecorder
        val display = virtualDisplay
        val descriptor = outputDescriptor
        val uri = outputUri
        val projection = mediaProjection
        val elapsed = elapsedMillis

        // Detach the fields first so a double tap can never call stop() twice.
        mediaRecorder = null
        virtualDisplay = null
        outputDescriptor = null
        outputUri = null
        mediaProjection = null

        frozenElapsedMillis = elapsed
        transition(RecordingContract.STATE_FINALIZING, reason)

        val task = Runnable {
            var muxed = false
            if (recorder != null) {
                // Draining the display stops new frames, which lets stop() return quickly.
                runCatching { display?.release() }
                    .onFailure { Log.w(TAG, "VirtualDisplay.release() failed", it) }
                try {
                    recorder.stop()
                    muxed = true
                } catch (error: RuntimeException) {
                    // Typical causes: zero frames encoded (screen was off) or the user revoked
                    // the projection. reset() returns the recorder to the Idle state.
                    Log.w(TAG, "MediaRecorder.stop() failed; discarding the take", error)
                    runCatching { recorder.reset() }
                }
                runCatching { recorder.release() }
            } else {
                runCatching { display?.release() }
            }

            runCatching { descriptor?.close() }

            val saved = uri != null && finaliseOutput(uri, muxed)
            val message = if (muxed && saved) {
                reason ?: getString(R.string.status_saved)
            } else {
                reason ?: getString(R.string.error_no_frames)
            }

            // Rule 4: back on a looper thread for the projection teardown + UI fan-out.
            mainHandler.post {
                projection?.let { releaseProjection(it) }
                transition(
                    if (muxed && saved) RecordingContract.STATE_IDLE else RecordingContract.STATE_ERROR,
                    message
                )
                stopForegroundAndSelf()
            }
        }

        var handedOff = false
        if (!synchronous) {
            try {
                finalizeExecutor.execute(task)
                handedOff = true
            } catch (rejected: RejectedExecutionException) {
                // onDestroy() may already have shut the pool down; finish inline so the
                // recording is never silently lost.
                Log.w(TAG, "Finalise executor rejected, running inline", rejected)
            }
        }
        if (!handedOff) task.run()
    }

    // ------------------------------------------------------------- MediaStore

    /**
     * Creates the pending MediaStore row up front. Writing through a Uri the provider hands us
     * is what lands the file in the public Movies/ScreenRecorder collection with zero storage
     * permissions, and IS_PENDING hides the half-written file from every other app until the
     * moov atom is flushed.
     */
    private fun createPendingOutput(): Uri {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$FILE_PREFIX$timestamp.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RecordingContract.RELATIVE_PATH)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: error("MediaStore rejected the insert for ${RecordingContract.RELATIVE_PATH}")
    }

    /** Returns true when the row is published and visible to gallery apps. */
    private fun finaliseOutput(uri: Uri, muxed: Boolean): Boolean {
        if (!muxed) {
            discardPendingOutput(uri)
            return false
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        return try {
            contentResolver.update(uri, values, null, null) == 1
        } catch (error: Exception) {
            Log.e(TAG, "Could not clear IS_PENDING on $uri", error)
            false
        }
    }

    private fun discardPendingOutput(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "Could not delete the rejected take $uri", it) }
    }

    // ------------------------------------------------------------- foreground

    private fun enterForeground(sessionState: String, config: RecorderConfig) {
        val notification = NotificationHelper.build(this, sessionState, elapsedMillis, config)
        ServiceCompat.startForeground(
            this,
            RecordingContract.NOTIFICATION_ID,
            notification,
            foregroundTypeMask(config)
        )
        foregroundStarted = true
    }

    /**
     * Android 14 validates the runtime type mask against both the manifest declaration and the
     * permissions actually held. Declaring `microphone` in the manifest but not starting with
     * it is fine; starting with it while RECORD_AUDIO is missing is a SecurityException - hence
     * the permission check here instead of trusting the switch alone.
     */
    private fun foregroundTypeMask(config: RecorderConfig): Int {
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val holdsMic = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (config.microphone && holdsMic) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return mask
    }

    private fun refreshNotification(sessionState: String) {
        if (!foregroundStarted) return
        val config = sessionConfig ?: return
        val manager = NotificationManagerCompat.from(this)
        // On 33+ the user may have denied POST_NOTIFICATIONS. The service notification that
        // startForeground() posted is still mandatory and stays, but re-notifying a hidden
        // channel is pointless work, so skip it.
        if (!manager.areNotificationsEnabled()) return
        manager.notify(RecordingContract.NOTIFICATION_ID, NotificationHelper.build(this, sessionState, elapsedMillis, config))
    }

    private fun stopForegroundAndSelf() {
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        sessionConfig = null
        stopSelf()
    }

    private fun releaseProjection(projection: MediaProjection) {
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        if (mediaProjection === projection) mediaProjection = null
    }

    // ------------------------------------------------------------- UI bridge

    private fun transition(sessionState: String, message: String?) {
        state = sessionState
        refreshNotification(sessionState)

        // Explicit (package-scoped) broadcast: on API 33+ the Activity registers for this with
        // RECEIVER_NOT_EXPORTED, so no other app can spoof a state change at us.
        sendBroadcast(
            Intent(RecordingContract.ACTION_STATE_CHANGED).apply {
                setPackage(packageName)
                putExtra(RecordingContract.EXTRA_STATE, sessionState)
                putExtra(RecordingContract.EXTRA_MESSAGE, message)
            }
        )
        observers.forEach { it.onStateChanged(sessionState, message) }
    }

    fun addObserver(observer: OnStateChange) {
        observers.add(observer)
        observer.onStateChanged(state, null)
    }

    fun removeObserver(observer: OnStateChange) {
        observers.remove(observer)
    }

    private fun fail(message: String) {
        frozenElapsedMillis = 0L
        transition(RecordingContract.STATE_ERROR, message)
        stopForegroundAndSelf()
    }

    @Suppress("DEPRECATION")
    private fun Intent.captureDataExtra(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(RecordingContract.EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            getParcelableExtra(RecordingContract.EXTRA_RESULT_DATA) as? Intent
        }
}
