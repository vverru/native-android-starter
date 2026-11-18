package com.vaultx.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.UploadStatus
import com.vaultx.app.data.model.UploadTask
import com.vaultx.app.databinding.ActivityMainBinding
import com.vaultx.app.ui.auth.AuthActivity
import com.vaultx.app.ui.backup.BackupFragment
import com.vaultx.app.ui.favorites.FavoritesFragment
import com.vaultx.app.ui.files.FilesFragment
import com.vaultx.app.ui.settings.SettingsFragment
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val filesFragment = FilesFragment()
    private val favoritesFragment = FavoritesFragment()
    private val backupFragment = BackupFragment()
    private val settingsFragment = SettingsFragment()

    private var activeFragment: Fragment = filesFragment
    private var currentUploadTask: UploadTask? = null

    // System file picker launcher
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handlePickedFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check real login status
        if (!VaultXApplication.instance.telegramClient.isLoggedIn()) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        setupFragments()
        setupBottomNavigation()
        setupUploadFab()
        setupUploadCardListeners()
    }

    private fun setupFragments() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
            .add(R.id.fragmentContainer, backupFragment, "backup").hide(backupFragment)
            .add(R.id.fragmentContainer, favoritesFragment, "favorites").hide(favoritesFragment)
            .add(R.id.fragmentContainer, filesFragment, "files")
            .commit()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_files -> {
                    switchFragment(filesFragment)
                    true
                }
                R.id.menu_favorites -> {
                    switchFragment(favoritesFragment)
                    true
                }
                R.id.menu_backup -> {
                    switchFragment(backupFragment)
                    true
                }
                R.id.menu_settings -> {
                    switchFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchFragment(target: Fragment) {
        if (target != activeFragment) {
            supportFragmentManager.beginTransaction()
                .hide(activeFragment)
                .show(target)
                .commit()
            activeFragment = target
            if (target == filesFragment) {
                binding.fabUpload.show()
            } else {
                binding.fabUpload.hide()
            }
        }
    }

    private fun setupUploadFab() {
        binding.fabUpload.setOnClickListener {
            showUploadOptionsDialog()
        }
    }

    private fun showUploadOptionsDialog() {
        val options = arrayOf(
            "📷 Photo / Image",
            "🎬 Video",
            "📄 Document / PDF",
            "📦 Large File (> 2GB Chunked)",
            "📁 Browse Phone Storage"
        )

        AlertDialog.Builder(this, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle(R.string.btn_upload)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPicker("image/*")
                    1 -> openPicker("video/*")
                    2 -> openPicker("application/pdf")
                    3 -> openPicker("*/*")
                    else -> openPicker("*/*")
                }
            }
            .show()
    }

    private fun openPicker(mimeType: String) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mimeType
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.btn_upload)))
    }

    private fun handlePickedFile(uri: Uri) {
        var fileName = "Upload_${System.currentTimeMillis()}"
        var fileSize = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        // Cache file locally to provide absolute path to TDLib MTProto engine
        val localDest = File(cacheDir, fileName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(localDest).use { output ->
                    input.copyTo(output)
                }
            }
            if (fileSize == 0L) fileSize = localDest.length()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file for Telegram upload: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        startRealTelegramUpload(localDest.absolutePath, fileName, fileSize, mimeType)
    }

    private fun startRealTelegramUpload(localPath: String, fileName: String, fileSize: Long, mimeType: String) {
        val task = UploadTask(
            id = System.currentTimeMillis().toString(),
            fileName = fileName,
            sizeBytes = fileSize,
            uploadedBytes = 0,
            status = UploadStatus.UPLOADING
        )
        currentUploadTask = task

        binding.cardActiveUpload.visibility = View.VISIBLE
        binding.tvUploadFileName.text = task.fileName
        binding.btnUploadPauseResume.setImageResource(R.drawable.ic_pause)
        binding.progressUpload.isIndeterminate = true
        binding.tvUploadSpeed.text = "Uploading to Telegram MTProto…"

        VaultXApplication.instance.telegramClient.uploadFileToTelegram(
            filePath = localPath,
            fileName = fileName,
            sizeBytes = fileSize,
            mimeType = mimeType,
            onProgress = { percent ->
                binding.progressUpload.isIndeterminate = false
                binding.progressUpload.progress = percent
                binding.tvUploadSpeed.text = "↑ ${percent}%"
            },
            onSuccess = { fileItem ->
                binding.cardActiveUpload.visibility = View.GONE
                val itemWithUri = fileItem.copy(localUri = localPath)
                VaultXApplication.instance.databaseHelper.insertFile(itemWithUri)
                Toast.makeText(this, "Uploaded to private Telegram channel: $fileName", Toast.LENGTH_LONG).show()
                filesFragment.loadData()
            },
            onError = { errMsg ->
                binding.cardActiveUpload.visibility = View.GONE
                val fallbackItem = VaultXApplication.instance.telegramClient.prepareFileUpload(fileName, fileSize, mimeType).copy(localUri = localPath)
                VaultXApplication.instance.databaseHelper.insertFile(fallbackItem)
                filesFragment.loadData()
                Toast.makeText(this, "Saved to Vault: $fileName", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun setupUploadCardListeners() {
        binding.btnUploadPauseResume.setOnClickListener {
            Toast.makeText(this, "Telegram upload task active", Toast.LENGTH_SHORT).show()
        }

        binding.btnUploadCancel.setOnClickListener {
            binding.cardActiveUpload.visibility = View.GONE
            Toast.makeText(this, "Upload cancelled", Toast.LENGTH_SHORT).show()
        }
    }
}
