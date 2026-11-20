package com.vaultx.app.ui.adapters

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
    private val onFavoriteToggle: (FileItem) -> Unit,
    private val onMoreOptionsClick: ((FileItem) -> Unit)? = null
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
            val isPhotoOrVideo = item.category == FileCategory.PHOTOS || item.category == FileCategory.VIDEOS || isImageOrVideo(item.name)

            // Resilient lookup: check localUri -> thumbnailUri -> vault_files folder
            val previewFile = item.localUri?.removePrefix("file://")?.let { File(it) }?.takeIf { it.exists() }
                ?: item.thumbnailUri?.removePrefix("file://")?.let { File(it) }?.takeIf { it.exists() }
                ?: findLocalVaultFile(itemView.context, item.name)

            if (previewFile != null) {
                if (isPhotoOrVideo) {
                    binding.ivIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(itemView.context)
                        .load(previewFile)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivIcon)
                } else if (item.category == FileCategory.PDFS || item.name.lowercase().endsWith(".pdf")) {
                    renderPdfThumbnail(previewFile, binding.ivIcon, placeholderIcon)
                } else {
                    binding.ivIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                    binding.ivIcon.setImageResource(placeholderIcon)
                }
            } else {
                binding.ivIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                binding.ivIcon.setImageResource(placeholderIcon)
                if (item.telegramMessageId > 0L) {
                    com.vaultx.app.VaultXApplication.instance.telegramClient.requestThumbnailForMessage(item.telegramMessageId)
                }
            }

            // Video play icon badge
            binding.ivPlayBadge.visibility = if (item.category == FileCategory.VIDEOS || item.name.lowercase().endsWith(".mp4") || item.name.lowercase().endsWith(".mkv")) View.VISIBLE else View.GONE

            val starRes = if (item.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            binding.ivFavorite.setImageResource(starRes)

            binding.root.setOnClickListener { onItemClick(item) }
            binding.ivIcon.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onMoreOptionsClick?.invoke(item)
                true
            }
            binding.btnMoreOptions.setOnClickListener {
                onMoreOptionsClick?.invoke(item)
            }

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
            val isPhotoOrVideo = item.category == FileCategory.PHOTOS || item.category == FileCategory.VIDEOS || isImageOrVideo(item.name)

            val previewFile = item.localUri?.removePrefix("file://")?.let { File(it) }?.takeIf { it.exists() }
                ?: item.thumbnailUri?.removePrefix("file://")?.let { File(it) }?.takeIf { it.exists() }
                ?: findLocalVaultFile(itemView.context, item.name)

            if (previewFile != null) {
                if (isPhotoOrVideo) {
                    binding.ivIconList.scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(itemView.context)
                        .load(previewFile)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivIconList)
                } else if (item.category == FileCategory.PDFS || item.name.lowercase().endsWith(".pdf")) {
                    renderPdfThumbnail(previewFile, binding.ivIconList, placeholderIcon)
                } else {
                    binding.ivIconList.scaleType = ImageView.ScaleType.FIT_CENTER
                    binding.ivIconList.setImageResource(placeholderIcon)
                }
            } else {
                binding.ivIconList.scaleType = ImageView.ScaleType.FIT_CENTER
                binding.ivIconList.setImageResource(placeholderIcon)
                if (item.telegramMessageId > 0L) {
                    com.vaultx.app.VaultXApplication.instance.telegramClient.requestThumbnailForMessage(item.telegramMessageId)
                }
            }

            val starRes = if (item.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            binding.ivFavoriteList.setImageResource(starRes)

            binding.root.setOnClickListener { onItemClick(item) }
            binding.ivIconList.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onMoreOptionsClick?.invoke(item)
                true
            }
            binding.btnMoreOptionsList.setOnClickListener {
                onMoreOptionsClick?.invoke(item)
            }

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

    private fun renderPdfThumbnail(file: File, imageView: ImageView, fallbackRes: Int) {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val width = (page.width / 2).coerceAtLeast(100)
                val height = (page.height / 2).coerceAtLeast(100)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                pfd.close()
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.setImageBitmap(bitmap)
            } else {
                renderer.close()
                pfd.close()
                imageView.setImageResource(fallbackRes)
            }
        } catch (_: Exception) {
            imageView.setImageResource(fallbackRes)
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

        fun findLocalVaultFile(context: android.content.Context, name: String): File? {
            return try {
                val dir = File(context.filesDir, "vault_files")
                if (!dir.exists()) return null
                val exact = File(dir, name)
                if (exact.exists()) return exact
                val files = dir.listFiles() ?: return null
                files.firstOrNull { it.name.endsWith("_$name") || it.name == name }
            } catch (_: Exception) {
                null
            }
        }

        fun isImageOrVideo(name: String): Boolean {
            return isImage(name) || isVideo(name)
        }

        fun isImage(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                   lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                   lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".svg")
        }

        fun isVideo(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") ||
                   lower.endsWith(".avi") || lower.endsWith(".3gp") || lower.endsWith(".webm")
        }
    }
}
