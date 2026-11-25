package com.example.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.screenrecorder.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * The whole UI is deliberately thin: it owns settings, permission gating and the consent
 * hand-off. The recording itself lives in [ScreenRecordService] so it survives the user
 * leaving the app, and the Activity is just a live view onto that service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { RecorderPrefs(this) }
    private val repository by lazy { RecordingRepository(this) }
    private val projectionManager by lazy {
        getSystemService(android.media.projection.MediaProjectionManager::class.java)
    }

    private lateinit var adapter: RecordingAdapter

    private val uiHandler = Handler(Looper.getMainLooper())
    private var service: ScreenRecordService? = null
    private var bound = false
    private var awaitingCapture = false
    private var refreshPending = false
    private var activeConfig: RecorderConfig? = null
    private var lastRenderedState: String = RecordingContract.STATE_IDLE

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val instance = (binder as? ScreenRecordService.LocalBinder)?.service ?: return
            service = instance
            instance.addObserver(stateObserver)
            renderState(instance.state, null, force = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            renderState(RecordingContract.STATE_IDLE, null, force = true)
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            renderState(RecordingContract.STATE_IDLE, null, force = true)
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
        }
    }

    private val stateObserver =
        ScreenRecordService.OnStateChange { state, message -> renderState(state, message) }

    /**
     * Broadcast path: catches state changes the binder cannot see, most importantly the user
     * tapping "Stop recording" in the notification while this Activity is stopped.
     */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(RecordingContract.EXTRA_STATE) ?: return
            val message = intent.getStringExtra(RecordingContract.EXTRA_MESSAGE)
            renderState(state, message, force = state == RecordingContract.STATE_ERROR)
        }
    }

    private var contentObserver: ContentObserver? = null

    private val ticker = object : Runnable {
        override fun run() {
            renderTimer()
            uiHandler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------- launchers

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                snack(getString(R.string.warn_notifications_denied))
            }
            // Not fatal: the service still runs, only the persistent notification is hidden.
            requestAudioThenCapture()
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchProjectionRequest()
            } else {
                // Degrade instead of dead-ending: a silent screen recording is still useful.
                prefs.microphone = false
                binding.switchMic.isChecked = false
                snack(getString(R.string.warn_mic_denied))
                launchProjectionRequest()
            }
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingCapture = false
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                snack(getString(R.string.warn_capture_cancelled))
                binding.btnRecord.isEnabled = true
                return@registerForActivityResult
            }

            val config = activeConfig ?: return@registerForActivityResult
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = RecordingContract.ACTION_START
                putExtra(RecordingContract.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordingContract.EXTRA_RESULT_DATA, data)
                config.putInto(this)
            }
            try {
                // startForegroundService (not bindService) is mandatory: the FGS has to exist
                // before the projection is handed over, and it must be started while this
                // Activity is still in the foreground for the Android 14 background-start ban.
                ContextCompat.startForegroundService(this, intent)
                renderState(RecordingContract.STATE_PREPARING, null, force = true)
            } catch (error: Exception) {
                snack(getString(R.string.error_service_blocked))
                binding.btnRecord.isEnabled = true
            }
        }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RecordingAdapter(
            repository = repository,
            onOpen = ::openRecording,
            onDelete = ::confirmDelete
        )
        binding.rvRecordings.apply {
            adapter = this@MainActivity.adapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            isNestedScrollingEnabled = false
            setHasFixedSize(true)
        }

        bindSettingsToPrefs()
        binding.btnRecord.setOnClickListener {
            if (service?.isBusy == true || isRecordingUi()) stopRecording() else startRecording()
        }
        binding.btnRefresh.setOnClickListener { reloadRecordings() }

        renderState(RecordingContract.STATE_IDLE, null, force = true)
        updateSpecLabel()
        reloadRecordings()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(RecordingContract.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // bindService() reports whether the connection will be alive; remembering it keeps the
        // onStop() unbind() honest (unbinding an unbound connection throws).
        bound = bindService(
            Intent(this, ScreenRecordService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        val observer = repository.observer(uiHandler) { scheduleReload() }
        contentObserver = observer
        contentResolver.registerContentObserver(repository.watchedUri, true, observer)

        uiHandler.post(ticker)
        // The service may have moved on (or finished) while we were stopped.
        renderState(service?.state ?: RecordingContract.STATE_IDLE, null, force = true)
        reloadRecordings()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(stateReceiver) }
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        contentObserver = null
        service?.removeObserver(stateObserver)
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        service = null
        uiHandler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------- setup

    private fun bindSettingsToPrefs() {
        val resolution = prefs.resolution
        binding.chipResolution.check(
            if (resolution == RecordingResolution.FHD1080) R.id.chip1080 else R.id.chip720
        )
        val frameRate = prefs.frameRate
        binding.chipFrameRate.check(
            if (frameRate == FrameRate.FPS_60) R.id.chip60 else R.id.chip30
        )
        binding.switchMic.isChecked = prefs.microphone

        binding.chipResolution.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            prefs.resolution = selectedResolution()
            updateSpecLabel()
        }
        binding.chipFrameRate.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            prefs.frameRate = selectedFrameRate()
            updateSpecLabel()
        }
        binding.switchMic.setOnCheckedChangeListener { _, isChecked ->
            prefs.microphone = isChecked
            updateSpecLabel()
        }
    }

    private fun selectedResolution(): RecordingResolution =
        if (binding.chip1080.isChecked) RecordingResolution.FHD1080 else RecordingResolution.HD720

    private fun selectedFrameRate(): FrameRate =
        if (binding.chip60.isChecked) FrameRate.FPS_60 else FrameRate.FPS_30

    private fun micRequested(): Boolean = binding.switchMic.isChecked

    /** Live read-out of what the chosen profile actually means for the encoder and the disk. */
    private fun updateSpecLabel() {
        val config = RecorderConfig.forDevice(
            this,
            selectedResolution(),
            selectedFrameRate(),
            micRequested()
        )
        binding.tvSpec.text = getString(
            R.string.spec_summary,
            config.width,
            config.height,
            config.frameRate,
            config.videoBitrate / 1_000_000,
            config.megabytesPerMinute
        )
        binding.tvPath.text =
            getString(R.string.save_location, RecordingContract.RELATIVE_PATH.trimEnd('/'))
    }

    private fun isRecordingUi(): Boolean =
        lastRenderedState == RecordingContract.STATE_RECORDING ||
            lastRenderedState == RecordingContract.STATE_PREPARING

    // ------------------------------------------------------------- start / stop

    private fun startRecording() {
        if (awaitingCapture || service?.isBusy == true) return
        if (!notificationsGranted()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestAudioThenCapture()
    }

    private fun requestAudioThenCapture() {
        if (micRequested() && !audioGranted()) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchProjectionRequest()
    }

    private fun launchProjectionRequest() {
        // The profile is frozen *before* the consent dialog so a rotation or a settings tweak
        // while the dialog is up cannot desync the Activity from the service.
        activeConfig = RecorderConfig.forDevice(
            this,
            selectedResolution(),
            selectedFrameRate(),
            micRequested() && audioGranted()
        )
        awaitingCapture = true
        binding.btnRecord.isEnabled = false
        try {
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (error: Exception) {
            awaitingCapture = false
            binding.btnRecord.isEnabled = true
            snack(getString(R.string.error_projection_unavailable))
        }
    }

    private fun stopRecording() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenRecordService::class.java).apply {
                    action = RecordingContract.ACTION_STOP
                }
            )
            renderState(RecordingContract.STATE_FINALIZING, null, force = true)
        } catch (error: Exception) {
            snack(getString(R.string.error_service_blocked))
        }
    }

    // ------------------------------------------------------------- rendering

    private fun renderState(state: String, message: String?, force: Boolean = false) {
        if (!force && state == lastRenderedState && message == null) return
        lastRenderedState = state

        val recording = state == RecordingContract.STATE_RECORDING
        val busy = state != RecordingContract.STATE_IDLE && state != RecordingContract.STATE_ERROR

        binding.btnRecord.isEnabled = !busy || state == RecordingContract.STATE_RECORDING
        binding.btnRecord.setText(
            when (state) {
                RecordingContract.STATE_PREPARING -> R.string.button_preparing
                RecordingContract.STATE_FINALIZING -> R.string.button_saving
                else -> if (recording) R.string.button_stop else R.string.button_start
            }
        )
        binding.btnRecord.setIconResource(
            if (recording) R.drawable.ic_stop_small else R.drawable.ic_videocam
        )

        binding.tvStatus.setText(
            when (state) {
                RecordingContract.STATE_PREPARING -> R.string.status_preparing
                RecordingContract.STATE_RECORDING -> R.string.status_recording
                RecordingContract.STATE_FINALIZING -> R.string.status_saving
                RecordingContract.STATE_ERROR -> R.string.status_error
                else -> R.string.status_idle
            }
        )
        binding.viewDot.setBackgroundResource(
            if (recording) R.drawable.bg_dot_rec else R.drawable.bg_dot_idle
        )
        if (recording) {
            binding.viewDot.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse)
            )
        } else {
            binding.viewDot.clearAnimation()
            binding.viewDot.alpha = 1f
        }

        setSettingsEnabled(!busy)

        // Keep the panel lit while we capture it; a dimmed screen records black frames.
        if (recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (!busy) {
            binding.tvWritten.visibility = View.GONE
            binding.tvTimer.text = getString(R.string.timer_zero)
        } else {
            binding.tvWritten.visibility = View.VISIBLE
            renderTimer()
        }

        if (message != null && state != RecordingContract.STATE_RECORDING) {
            snack(message)
        }
        if (state == RecordingContract.STATE_IDLE || state == RecordingContract.STATE_ERROR) {
            // The provider publishes the row a moment after IS_PENDING is cleared, so let the
            // ContentObserver settle before repainting the list.
            scheduleReload()
        }
    }

    private fun renderTimer() {
        val elapsed = service?.elapsedMillis ?: 0L
        binding.tvTimer.text = formatClock(elapsed)
        val config = activeConfig
        if (config != null && elapsed > 0) {
            val bytes = (config.videoBitrate + config.audioBitrate).toLong() / 8L * (elapsed / 1000L)
            binding.tvWritten.text = getString(
                R.string.written_estimate,
                android.text.format.Formatter.formatShortFileSize(this, bytes)
            )
        }
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        binding.chipResolution.isEnabled = enabled
        binding.chipFrameRate.isEnabled = enabled
        binding.switchMic.isEnabled = enabled
        binding.chip720.isEnabled = enabled
        binding.chip1080.isEnabled = enabled
        binding.chip30.isEnabled = enabled
        binding.chip60.isEnabled = enabled
    }

    // ------------------------------------------------------------- recordings list

    private fun scheduleReload() {
        if (refreshPending) return
        refreshPending = true
        uiHandler.postDelayed(
            {
                refreshPending = false
                reloadRecordings()
            },
            350L
        )
    }

    private fun reloadRecordings() {
        lifecycleScope.launch {
            val items = repository.recent()
            adapter.submitList(items)
            binding.tvCount.text = resources.getQuantityString(
                R.plurals.recordings_count,
                items.size,
                items.size
            )
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.rvRecordings.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun openRecording(recording: Recording) {
        try {
            startActivity(
                Intent.createChooser(
                    repository.playCommand(recording),
                    getString(R.string.chooser_play)
                )
            )
        } catch (error: Exception) {
            snack(getString(R.string.error_no_player))
        }
    }

    private fun confirmDelete(recording: Recording) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, recording.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = repository.delete(recording)
                snack(
                    getString(
                        if (deleted) R.string.toast_deleted else R.string.error_delete_denied
                    )
                )
                reloadRecordings()
            }
            .show()
    }

    // ------------------------------------------------------------- helpers

    private fun notificationsGranted(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun audioGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun snack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
