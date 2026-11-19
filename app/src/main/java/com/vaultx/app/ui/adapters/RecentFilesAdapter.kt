package com.vaultx.app.ui.adapters

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
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

            val isLocal = !item.localUri.isNullOrBlank() && File(item.localUri!!).exists()
            if (isLocal) {
                val file = File(item.localUri!!)
                if (item.category == FileCategory.PHOTOS || item.category == FileCategory.VIDEOS) {
                    binding.ivRecentIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(itemView.context)
                        .load(file)
                        .centerCrop()
                        .placeholder(placeholderIcon)
                        .error(placeholderIcon)
                        .into(binding.ivRecentIcon)
                } else if (item.category == FileCategory.PDFS || item.name.lowercase().endsWith(".pdf")) {
                    renderPdfThumbnail(file, binding.ivRecentIcon, placeholderIcon)
                } else {
                    binding.ivRecentIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                    binding.ivRecentIcon.setImageResource(placeholderIcon)
                }
            } else {
                binding.ivRecentIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                binding.ivRecentIcon.setImageResource(placeholderIcon)
            }

            binding.root.setOnClickListener { onItemClick(item) }
        }

        private fun renderPdfThumbnail(file: File, imageView: ImageView, fallbackRes: Int) {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
                    val width = (page.width / 2).coerceAtLeast(80)
                    val height = (page.height / 2).coerceAtLeast(80)
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
    }
}
