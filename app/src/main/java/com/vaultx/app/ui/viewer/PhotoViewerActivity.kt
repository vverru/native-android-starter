package com.vaultx.app.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.databinding.ActivityPhotoViewerBinding
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding
    private var fileId: Long = 0
    private var isFav: Boolean = false
    private var currentFileName: String = "Photo"
    private var currentFileUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentFileName = intent.getStringExtra("file_name") ?: "Photo"
        val fileSize = intent.getStringExtra("file_size") ?: ""
        currentFileUri = intent.getStringExtra("file_uri")
        fileId = intent.getLongExtra("file_id", 0)
        isFav = intent.getBooleanExtra("is_fav", false)

        binding.tvViewerFileName.text = currentFileName
        binding.tvViewerFileSize.text = fileSize
        updateFavIcon()

        loadImage()

        binding.btnPhotoBack.setOnClickListener { finish() }

        // Favorite Toggle
        binding.ivViewerFav.setOnClickListener {
            isFav = !isFav
            VaultXApplication.instance.databaseHelper.setFavorite(fileId, isFav)
            updateFavIcon()
            val msg = if (isFav) getString(R.string.action_favorite) else getString(R.string.action_unfavorite)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Rename Image
        binding.btnPhotoRename.setOnClickListener {
            showRenameDialog()
        }

        // Crop & Rotate Image
        binding.btnPhotoCrop.setOnClickListener {
            showEditCropDialog()
        }

        binding.btnPhotoDownload.setOnClickListener {
            Toast.makeText(this, "Downloading $currentFileName to phone storage…", Toast.LENGTH_SHORT).show()
        }

        binding.btnPhotoShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "VaultX File: $currentFileName (Streamed directly from Telegram)")
            }
            startActivity(Intent.createChooser(shareIntent, "Share file"))
        }

        binding.btnPhotoDelete.setOnClickListener {
            AlertDialog.Builder(this, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
                .setTitle("Delete from Vault & Telegram?")
                .setMessage("Delete '$currentFileName'?\n\nThis will permanently delete this photo from your phone AND from your private Telegram Cloud channel.")
                .setPositiveButton("Delete Permanently") { _, _ ->
                    val db = VaultXApplication.instance.databaseHelper
                    val item = db.findFileByName(currentFileName)
                    if (item != null) {
                        VaultXApplication.instance.telegramClient.deleteFileFromCloudAndLocal(item) {
                            Toast.makeText(this, "Deleted from Vault & Telegram channel", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    } else {
                        db.deletePermanently(fileId)
                        Toast.makeText(this, "Deleted permanently", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadImage() {
        var fileToLoad = currentFileUri?.let { File(it) }?.takeIf { it.exists() }
        if (fileToLoad == null) {
            fileToLoad = com.vaultx.app.ui.adapters.FileAdapter.findLocalVaultFile(this, currentFileName)
            if (fileToLoad != null) {
                currentFileUri = fileToLoad.absolutePath
            }
        }

        if (fileToLoad != null && fileToLoad.exists()) {
            Glide.with(this)
                .load(fileToLoad)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .fitCenter()
                .placeholder(R.drawable.ic_photo)
                .error(R.drawable.ic_photo)
                .into(binding.ivFullscreenPhoto)
        } else if (!currentFileUri.isNullOrBlank()) {
            Glide.with(this)
                .load(currentFileUri)
                .fitCenter()
                .placeholder(R.drawable.ic_photo)
                .error(R.drawable.ic_photo)
                .into(binding.ivFullscreenPhoto)
        } else {
            binding.ivFullscreenPhoto.setImageResource(R.drawable.ic_photo)
        }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(currentFileName)
            setSelection(text.length)
            setTextColor(getColor(R.color.vaultx_text_primary))
        }
        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 0)
            addView(input)
        }

        AlertDialog.Builder(this, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("Rename Image")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != currentFileName) {
                    var newUri: String? = null
                    currentFileUri?.let { uri ->
                        val oldFile = File(uri)
                        if (oldFile.exists()) {
                            val newFile = File(oldFile.parentFile, newName)
                            if (oldFile.renameTo(newFile)) {
                                newUri = newFile.absolutePath
                                currentFileUri = newUri
                            }
                        }
                    }
                    VaultXApplication.instance.databaseHelper.renameFile(fileId, newName, newUri)
                    currentFileName = newName
                    binding.tvViewerFileName.text = currentFileName
                    Toast.makeText(this, "Renamed to $newName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditCropDialog() {
        val options = arrayOf(
            "🔄 Rotate 90° Clockwise",
            "📐 Crop Square (1:1)",
            "📺 Crop Landscape (16:9)",
            "📱 Crop Vertical (9:16)"
        )

        AlertDialog.Builder(this, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("Edit & Crop Image")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> rotateImage()
                    1 -> cropImageRatio(1, 1)
                    2 -> cropImageRatio(16, 9)
                    3 -> cropImageRatio(9, 16)
                }
            }
            .show()
    }

    private fun getLocalImageFile(): File? {
        val uri = currentFileUri ?: return null
        val file = File(uri)
        return if (file.exists()) file else null
    }

    private fun rotateImage() {
        val file = getLocalImageFile()
        if (file == null) {
            Toast.makeText(this, "Original file is not cached locally for editing", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val matrix = Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            FileOutputStream(file).use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bitmap.recycle()
            rotated.recycle()

            loadImage()
            Toast.makeText(this, "Rotated 90°", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to rotate: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cropImageRatio(ratioX: Int, ratioY: Int) {
        val file = getLocalImageFile()
        if (file == null) {
            Toast.makeText(this, "Original file is not cached locally for editing", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val srcWidth = bitmap.width
            val srcHeight = bitmap.height

            val targetAspect = ratioX.toFloat() / ratioY.toFloat()
            val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()

            val cropWidth: Int
            val cropHeight: Int
            val cropX: Int
            val cropY: Int

            if (srcAspect > targetAspect) {
                // Image is wider than desired ratio -> crop width
                cropHeight = srcHeight
                cropWidth = (srcHeight * targetAspect).toInt()
                cropX = (srcWidth - cropWidth) / 2
                cropY = 0
            } else {
                // Image is taller than desired ratio -> crop height
                cropWidth = srcWidth
                cropHeight = (srcWidth / targetAspect).toInt()
                cropX = 0
                cropY = (srcHeight - cropHeight) / 2
            }

            val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)

            FileOutputStream(file).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bitmap.recycle()
            cropped.recycle()

            loadImage()
            Toast.makeText(this, "Cropped to $ratioX:$ratioY", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to crop: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFavIcon() {
        binding.ivViewerFav.setImageResource(
            if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }
}
