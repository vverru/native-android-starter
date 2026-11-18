package com.vaultx.app.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.databinding.ActivityPhotoViewerBinding

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding
    private var fileId: Long = 0
    private var isFav: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fileName = intent.getStringExtra("file_name") ?: "Photo"
        val fileSize = intent.getStringExtra("file_size") ?: ""
        fileId = intent.getLongExtra("file_id", 0)
        isFav = intent.getBooleanExtra("is_fav", false)

        binding.tvViewerFileName.text = fileName
        binding.tvViewerFileSize.text = fileSize
        updateFavIcon()

        binding.btnPhotoBack.setOnClickListener { finish() }

        binding.ivViewerFav.setOnClickListener {
            isFav = !isFav
            VaultXApplication.instance.databaseHelper.setFavorite(fileId, isFav)
            updateFavIcon()
            val msg = if (isFav) getString(R.string.action_favorite) else getString(R.string.action_unfavorite)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnPhotoDownload.setOnClickListener {
            Toast.makeText(this, "Downloading $fileName to phone storage…", Toast.LENGTH_SHORT).show()
        }

        binding.btnPhotoShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "VaultX File: $fileName (Streamed directly from Telegram)")
            }
            startActivity(Intent.createChooser(shareIntent, "Share file"))
        }

        binding.btnPhotoDelete.setOnClickListener {
            VaultXApplication.instance.databaseHelper.moveToTrash(fileId)
            Toast.makeText(this, "Moved to Trash", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateFavIcon() {
        binding.ivViewerFav.setImageResource(
            if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }
}
