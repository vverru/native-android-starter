package com.vaultx.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val filesFragment = FilesFragment()
    private val favoritesFragment = FavoritesFragment()
    private val backupFragment = BackupFragment()
    private val settingsFragment = SettingsFragment()

    private var activeFragment: Fragment = filesFragment
    private var currentUploadTask: UploadTask? = null
    private var uploadHandler: Handler? = null
    private var uploadRunnable: Runnable? = null

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

        // Check login
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
                    3 -> simulateLargeChunkedUpload()
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
        var fileSize = 12_400_000L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        startUpload(fileName, fileSize, mimeType)
    }

    private fun simulateLargeChunkedUpload() {
        val largeFileName = "Dune_Part2_4K_HDR.mkv"
        val largeFileSize = 3_400_000_000L // 3.4 GB
        startUpload(largeFileName, largeFileSize, "video/x-matroska")
    }

    private fun startUpload(fileName: String, fileSize: Long, mimeType: String) {
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
        binding.progressUpload.progress = 0

        runUploadSimulation(task, mimeType)
    }

    private fun runUploadSimulation(task: UploadTask, mimeType: String) {
        uploadHandler = Handler(Looper.getMainLooper())
        val stepBytes = task.sizeBytes / 20

        uploadRunnable = object : Runnable {
            override fun run() {
                if (task.status == UploadStatus.PAUSED) return

                task.uploadedBytes = (task.uploadedBytes + stepBytes).coerceAtMost(task.sizeBytes)
                val percent = task.progressPercent

                binding.progressUpload.progress = percent
                binding.tvUploadBytes.text = "${formatBytes(task.uploadedBytes)} / ${formatBytes(task.sizeBytes)}"
                binding.tvUploadSpeed.text = "↑ ${task.formattedSpeed} • ${percent}%"

                if (task.uploadedBytes >= task.sizeBytes) {
                    // Upload Completed!
                    task.status = UploadStatus.COMPLETED
                    binding.cardActiveUpload.visibility = View.GONE

                    // Insert to database
                    val fileItem = VaultXApplication.instance.telegramClient.prepareFileUpload(
                        task.fileName,
                        task.sizeBytes,
                        mimeType
                    )
                    VaultXApplication.instance.databaseHelper.insertFile(fileItem)

                    Toast.makeText(this@MainActivity, "Uploaded to private Telegram channel: ${task.fileName}", Toast.LENGTH_LONG).show()

                    // Refresh files fragment
                    filesFragment.loadData()
                } else {
                    uploadHandler?.postDelayed(this, 300)
                }
            }
        }
        uploadHandler?.post(uploadRunnable!!)
    }

    private fun setupUploadCardListeners() {
        // Pause / Resume
        binding.btnUploadPauseResume.setOnClickListener {
            val task = currentUploadTask ?: return@setOnClickListener
            if (task.status == UploadStatus.UPLOADING) {
                task.status = UploadStatus.PAUSED
                binding.btnUploadPauseResume.setImageResource(R.drawable.ic_play)
                binding.tvUploadSpeed.text = getString(R.string.upload_paused)
                Toast.makeText(this, "Upload paused", Toast.LENGTH_SHORT).show()
            } else {
                task.status = UploadStatus.UPLOADING
                binding.btnUploadPauseResume.setImageResource(R.drawable.ic_pause)
                uploadRunnable?.let { uploadHandler?.post(it) }
                Toast.makeText(this, "Upload resumed", Toast.LENGTH_SHORT).show()
            }
        }

        // Cancel
        binding.btnUploadCancel.setOnClickListener {
            uploadHandler?.removeCallbacksAndMessages(null)
            currentUploadTask?.status = UploadStatus.FAILED
            binding.cardActiveUpload.visibility = View.GONE
            Toast.makeText(this, "Upload cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        val gb = mb / 1024.0
        return if (gb >= 1.0) String.format("%.2f GB", gb) else String.format("%.1f MB", mb)
    }

    override fun onDestroy() {
        super.onDestroy()
        uploadHandler?.removeCallbacksAndMessages(null)
    }
}
