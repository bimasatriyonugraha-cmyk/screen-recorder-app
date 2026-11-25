package com.example.screenrecorder

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One published row from Movies/ScreenRecorder. */
data class Recording(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateMillis: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int
)

/**
 * Read/write gateway over MediaStore. Because every row here was inserted by this app, the
 * provider grants us edit/delete access without MANAGE_EXTERNAL_STORAGE or any legacy flag.
 */
class RecordingRepository(private val context: Context) {

    private val collection: Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT
    )

    val watchedUri: Uri get() = collection

    fun observer(handler: Handler, onChange: () -> Unit): ContentObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
            override fun onChange(selfChange: Boolean) = onChange()
        }

    suspend fun recent(): List<Recording> = withContext(Dispatchers.IO) {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" +
            " AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("%${RecordingContract.ALBUM_NAME}%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC LIMIT $MAX_ROWS"

        val results = ArrayList<Recording>(MAX_ROWS)
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTaken = cursor.getLong(takenColumn)
                val dateAddedSeconds = cursor.getLong(addedColumn)
                results += Recording(
                    id = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    displayName = cursor.getString(nameColumn).orEmpty(),
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    dateMillis = if (dateTaken > 0L) dateTaken else dateAddedSeconds * 1000L,
                    durationMillis = cursor.getLong(durationColumn).coerceAtLeast(0L),
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn)
                )
            }
        }
        results
    }

    /**
     * Thumbnails come from the media provider's own cache, so there is no file-path decoding
     * and no MediaMetadataRetriever instance per row. Returns null while the row is still being
     * scanned, in which case the list simply shows its placeholder.
     */
    fun loadThumbnail(recording: Recording, size: Size = THUMB_SIZE): Bitmap? =
        runCatching {
            context.contentResolver.loadThumbnail(recording.uri, size, null)
        }.getOrNull()

    fun playCommand(recording: Recording): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(recording.uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun delete(recording: Recording): Boolean = try {
        context.contentResolver.delete(recording.uri, null, null) > 0
    } catch (error: Exception) {
        Log.w(TAG, "Delete refused for ${recording.uri}", error)
        false
    }

    private companion object {
        const val MAX_ROWS = 60
        const val TAG = "RecordingRepository"
        val THUMB_SIZE = Size(168, 168)
    }
}
