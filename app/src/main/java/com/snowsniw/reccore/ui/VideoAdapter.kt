package com.snowsniw.reccore.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.snowsniw.reccore.R
import com.snowsniw.reccore.data.VideoItem
import com.snowsniw.reccore.data.VideoRepository
import com.snowsniw.reccore.databinding.ItemVideoBinding
import java.util.Locale

class VideoAdapter(
    private val onPlayClick: (VideoItem) -> Unit,
    private val onShareClick: (VideoItem) -> Unit,
    private val onDeleteClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val items = mutableListOf<VideoItem>()

    fun submitList(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(private val binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VideoItem) {
            binding.tvVideoTitle.text = item.name

            val totalSec = item.durationMs / 1000
            val mins = totalSec / 60
            val secs = totalSec % 60
            val durStr = String.format(Locale.US, "%02d:%02d", mins, secs)
            val sizeMb = String.format(Locale.US, "%.1f MB", item.sizeBytes / (1024f * 1024f))
            val resStr = if (item.width > 0 && item.height > 0) "${item.width}x${item.height} • " else ""

            binding.tvVideoMeta.text = "$resStr$durStr • $sizeMb"

            val thumb = VideoRepository.loadThumbnail(binding.root.context, item.uri)
            if (thumb != null) {
                binding.ivThumbnail.setImageBitmap(thumb)
            } else {
                binding.ivThumbnail.setImageResource(R.drawable.ic_tab_video)
            }

            binding.root.setOnClickListener { onPlayClick(item) }
            binding.btnShareVideo.setOnClickListener { onShareClick(item) }
            binding.btnDeleteVideo.setOnClickListener { onDeleteClick(item) }
        }
    }
}
