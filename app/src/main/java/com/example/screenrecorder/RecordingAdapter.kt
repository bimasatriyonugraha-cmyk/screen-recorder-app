package com.example.screenrecorder

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.screenrecorder.databinding.ItemRecordingBinding
import java.util.concurrent.Executors

/**
 * Plain [ListAdapter] with DiffUtil: no paging library is needed for a folder of files and no
 * image-loader dependency is needed either, because thumbnails come straight out of the media
 * provider's own cache.
 */
class RecordingAdapter(
    private val repository: RecordingRepository,
    private val onOpen: (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit
) : ListAdapter<Recording, RecordingAdapter.Holder>(DIFF) {

    private val thumbExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        holder.clearThumbnail()
    }

    inner class Holder(private val binding: ItemRecordingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var thumbnailToken: Any? = null

        fun clearThumbnail() {
            thumbnailToken = null
            binding.ivThumb.setImageDrawable(null)
        }

        fun bind(item: Recording) {
            val context = binding.root.context
            thumbnailToken = item.uri

            binding.tvName.text = item.displayName
                .removePrefix("REC_")
                .removeSuffix(".mp4")
                .replace('_', ' ')
            binding.tvDuration.text = formatClock(item.durationMillis)
            binding.tvMeta.text = buildString {
                append(
                    context.getString(
                        R.string.recording_dimensions,
                        item.width,
                        item.height
                    )
                )
                append(" · ")
                append(Formatter.formatShortFileSize(context, item.sizeBytes))
                append(" · ")
                append(
                    DateUtils.getRelativeTimeSpanString(
                        item.dateMillis,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    )
                )
            }

            binding.root.setOnClickListener { onOpen(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }

            loadThumbnail(item)
        }

        private fun loadThumbnail(item: Recording) {
            thumbExecutor.execute {
                val bitmap: Bitmap? = repository.loadThumbnail(item)
                mainHandler.post {
                    // Guard against recycling: only paint if this row still wants that file.
                    if (bitmap != null && thumbnailToken == item.uri) {
                        binding.ivThumb.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Recording>() {
            override fun areItemsTheSame(oldItem: Recording, newItem: Recording) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Recording, newItem: Recording) =
                oldItem == newItem
        }
    }
}
