package com.vaultx.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vaultx.app.R
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.databinding.ItemRecentFileBinding
import java.io.File

class RecentFilesAdapter(
    private var recentFiles: List<FileItem>,
    private val onItemClick: (FileItem) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.RecentViewHolder>() {

    fun updateData(newFiles: List<FileItem>) {
        recentFiles = newFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val binding = ItemRecentFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RecentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        holder.bind(recentFiles[position])
    }

    override fun getItemCount(): Int = recentFiles.size

    inner class RecentViewHolder(private val binding: ItemRecentFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) {
            binding.tvRecentName.text = item.name
            binding.tvRecentSize.text = item.formattedSize

            val placeholderIcon = when (item.category) {
                FileCategory.PHOTOS -> R.drawable.ic_photo
                FileCategory.VIDEOS -> R.drawable.ic_video
                FileCategory.PDFS -> R.drawable.ic_pdf
                FileCategory.DOCS -> R.drawable.ic_doc
                FileCategory.AUDIO -> R.drawable.ic_audio
                FileCategory.ALL -> R.drawable.ic_folder
            }

            if ((item.category == FileCategory.PHOTOS || item.category == FileCategory.VIDEOS) && item.localUri != null) {
                val file = File(item.localUri)
                if (file.exists()) {
                    Glide.with(itemView.context)
                        .load(file)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivRecentIcon)
                } else {
                    Glide.with(itemView.context)
                        .load(item.localUri)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivRecentIcon)
                }
            } else {
                binding.ivRecentIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                binding.ivRecentIcon.setImageResource(placeholderIcon)
            }

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
