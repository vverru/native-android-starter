package com.vaultx.app.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.vaultx.app.databinding.ActivityPdfViewerBinding
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentPageIndex: Int = 0
    private var totalPages: Int = 0
    private var pdfFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fileName = intent.getStringExtra("file_name") ?: "Document.pdf"
        val fileUri = intent.getStringExtra("file_uri")

        binding.tvPdfFileName.text = fileName
        binding.btnPdfBack.setOnClickListener { finish() }

        binding.btnPdfShare.setOnClickListener {
            sharePdf()
        }

        binding.btnPrevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                renderPage(currentPageIndex - 1)
            }
        }

        binding.btnNextPage.setOnClickListener {
            if (currentPageIndex < totalPages - 1) {
                renderPage(currentPageIndex + 1)
            }
        }

        if (!fileUri.isNullOrBlank()) {
            val file = File(fileUri)
            if (file.exists()) {
                pdfFile = file
                openPdfRenderer(file)
            } else {
                showEmpty("PDF file not found locally")
            }
        } else {
            showEmpty("Invalid PDF file URI")
        }
    }

    private fun openPdfRenderer(file: File) {
        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor?.let {
                pdfRenderer = PdfRenderer(it)
                totalPages = pdfRenderer?.pageCount ?: 0
                if (totalPages > 0) {
                    binding.tvPdfEmpty.visibility = View.GONE
                    renderPage(0)
                } else {
                    showEmpty("PDF has no readable pages")
                }
            }
        } catch (e: Exception) {
            showEmpty("Error opening PDF: ${e.message}")
        }
    }

    private fun renderPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= totalPages) return

        try {
            currentPage?.close()
            val page = renderer.openPage(index)
            currentPage = page
            currentPageIndex = index

            // Render high-res crisp bitmap for crisp text reading
            val density = resources.displayMetrics.density
            val scale = (density * 1.5f).coerceAtLeast(1f)
            val bitmapWidth = (page.width * scale).toInt()
            val bitmapHeight = (page.height * scale).toInt()

            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            binding.ivPdfPage.setImageBitmap(bitmap)

            val pageStr = "Page ${index + 1} of $totalPages"
            binding.tvPdfPageIndicator.text = pageStr
            binding.tvCurrentPageInfo.text = "${index + 1} / $totalPages"

            binding.btnPrevPage.isEnabled = index > 0
            binding.btnNextPage.isEnabled = index < totalPages - 1
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to render page: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEmpty(message: String) {
        binding.tvPdfEmpty.text = message
        binding.tvPdfEmpty.visibility = View.VISIBLE
        binding.tvPdfPageIndicator.text = "0 Pages"
        binding.tvCurrentPageInfo.text = "- / -"
        binding.btnPrevPage.isEnabled = false
        binding.btnNextPage.isEnabled = false
    }

    private fun sharePdf() {
        val file = pdfFile ?: return
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share PDF"))
        } catch (_: Exception) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "VaultX File: ${file.name}")
            }
            startActivity(Intent.createChooser(shareIntent, "Share file"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            currentPage?.close()
            pdfRenderer?.close()
            fileDescriptor?.close()
        } catch (_: Exception) {}
    }
}
