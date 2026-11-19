package com.vaultx.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vaultx.app.R
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.databinding.ItemFileGridBinding
import com.vaultx.app.databinding.ItemFileListBinding
import java.io.File

class FileAdapter(
    private var files: List<FileItem>,
    var isGridMode: Boolean = true,
    private val onItemClick: (FileItem) -> Unit,
    private val onFavoriteToggle: (FileItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun updateData(newFiles: List<FileItem>) {
        files = newFiles
        notifyDataSetChanged()
    }

    fun getFiles(): List<FileItem> = files

    fun removeFileAt(position: Int) {
        if (position in files.indices) {
            val mutable = files.toMutableList()
            mutable.removeAt(position)
            files = mutable
            notifyItemRemoved(position)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (isGridMode) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val binding = ItemFileGridBinding.inflate(inflater, parent, false)
            GridViewHolder(binding)
        } else {
            val binding = ItemFileListBinding.inflate(inflater, parent, false)
            ListViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = files[position]
        if (holder is GridViewHolder) {
            holder.bind(item)
        } else if (holder is ListViewHolder) {
            holder.bind(item)
        }
    }

    override fun getItemCount(): Int = files.size

    inner class GridViewHolder(private val binding: ItemFileGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) {
            binding.tvFileName.text = item.name
            binding.tvFileSize.text = item.formattedSize

            val placeholderIcon = getCategoryIcon(item.category)

            if ((item.category == FileCategory.PHOTOS || item.category == FileCategory.VIDEOS) && item.localUri != null) {
                val file = File(item.localUri)
                if (file.exists()) {
                    Glide.with(itemView.context)
                        .load(file)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivIcon)
                } else {
                    Glide.with(itemView.context)
                        .load(item.localUri)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivIcon)
                }
            } else {
                binding.ivIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                binding.ivIcon.setImageResource(placeholderIcon)
            }

            // Video play icon badge
            binding.ivPlayBadge.visibility = if (item.category == FileCategory.VIDEOS) View.VISIBLE else View.GONE

            val starRes = if (item.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            binding.ivFavorite.setImageResource(starRes)

            binding.root.setOnClickListener { onItemClick(item) }

            // Instant 0ms optimistic favorite toggle with tactile pop animation
            binding.ivFavorite.setOnClickListener {
                item.isFavorite = !item.isFavorite
                binding.ivFavorite.setImageResource(
                    if (item.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
                )
                binding.ivFavorite.animate().scaleX(1.35f).scaleY(1.35f).setDuration(80).withEndAction {
                    binding.ivFavorite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                }.start()
                onFavoriteToggle(item)
            }
        }
    }

    inner class ListViewHolder(private val binding: ItemFileListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) {
            binding.tvFileNameList.text = item.name
            binding.tvFileMetaList.text = "${item.formattedSize} • ${item.category.name}"

            val placeholderIcon = getCategoryIcon(item.category)

            if ((item.category == FileCategory.PHOTOS || item.category == FileCategory.VIDEOS) && item.localUri != null) {
                val file = File(item.localUri)
                if (file.exists()) {
                    Glide.with(itemView.context)
                        .load(file)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivIconList)
                } else {
                    Glide.with(itemView.context)
                        .load(item.localUri)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivIconList)
                }
            } else {
                binding.ivIconList.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                binding.ivIconList.setImageResource(placeholderIcon)
            }

            val starRes = if (item.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            binding.ivFavoriteList.setImageResource(starRes)

            binding.root.setOnClickListener { onItemClick(item) }

            // Instant 0ms optimistic favorite toggle with tactile pop animation
            binding.ivFavoriteList.setOnClickListener {
                item.isFavorite = !item.isFavorite
                binding.ivFavoriteList.setImageResource(
                    if (item.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
                )
                binding.ivFavoriteList.animate().scaleX(1.35f).scaleY(1.35f).setDuration(80).withEndAction {
                    binding.ivFavoriteList.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                }.start()
                onFavoriteToggle(item)
            }
        }
    }

    private fun getCategoryIcon(category: FileCategory): Int {
        return when (category) {
            FileCategory.PHOTOS -> R.drawable.ic_photo
            FileCategory.VIDEOS -> R.drawable.ic_video
            FileCategory.PDFS -> R.drawable.ic_pdf
            FileCategory.DOCS -> R.drawable.ic_doc
            FileCategory.AUDIO -> R.drawable.ic_audio
            FileCategory.ALL -> R.drawable.ic_folder
        }
    }

    companion object {
        const val VIEW_TYPE_GRID = 1
        const val VIEW_TYPE_LIST = 2
    }
}
