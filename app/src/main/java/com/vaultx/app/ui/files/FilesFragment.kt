package com.vaultx.app.ui.files

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.databinding.FragmentFilesBinding
import com.vaultx.app.ui.adapters.FileAdapter
import com.vaultx.app.ui.adapters.RecentFilesAdapter
import com.vaultx.app.ui.viewer.PhotoViewerActivity
import com.vaultx.app.ui.viewer.VideoPlayerActivity

class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private lateinit var fileAdapter: FileAdapter
    private lateinit var recentAdapter: RecentFilesAdapter

    private var currentCategory = FileCategory.ALL
    private var currentSearch = ""
    private var currentSort = "date_desc"
    private var isGrid = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tg = VaultXApplication.instance.telegramClient
        if (tg.privateChannelId != 0L) {
            binding.tvCloudChannelStatus.text = "● ${tg.privateChannelTitle} • Synced"
        }

        setupRecyclerViews()
        setupListeners()
        loadData()
    }

    private fun setupRecyclerViews() {
        // Recent files horizontal rail
        recentAdapter = RecentFilesAdapter(emptyList()) { file -> openFile(file) }
        binding.rvRecentFiles.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentFiles.adapter = recentAdapter

        // Main files adapter
        fileAdapter = FileAdapter(
            files = emptyList(),
            isGridMode = isGrid,
            onItemClick = { file -> openFile(file) },
            onFavoriteToggle = { file ->
                Thread {
                    VaultXApplication.instance.databaseHelper.setFavorite(file.id, file.isFavorite)
                }.start()
                val msg = if (file.isFavorite) getString(R.string.action_favorite) else getString(R.string.action_unfavorite)
                Toast.makeText(requireContext(), "${file.name} $msg", Toast.LENGTH_SHORT).show()
            },
            onMoreOptionsClick = { file ->
                showFileOptionsSheet(file)
            }
        )
        updateLayoutManager()
        binding.rvFiles.adapter = fileAdapter
    }

    private fun showFileOptionsSheet(file: FileItem) {
        val sheet = FileOptionsBottomSheet.newInstance(
            file = file,
            onOpen = { openFile(it) },
            onFavoriteToggle = {
                Thread {
                    VaultXApplication.instance.databaseHelper.setFavorite(it.id, it.isFavorite)
                }.start()
                loadData()
            },
            onRename = { targetFile, newName ->
                VaultXApplication.instance.databaseHelper.renameFile(targetFile.id, newName)
                loadData()
                Toast.makeText(requireContext(), "Renamed to $newName", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                loadData()
            }
        )
        sheet.show(childFragmentManager, "file_options")
    }

    private fun updateLayoutManager() {
        binding.rvFiles.layoutManager = if (isGrid) {
            GridLayoutManager(requireContext(), 3)
        } else {
            LinearLayoutManager(requireContext())
        }
    }

    private fun setupListeners() {
        // Search filter
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentSearch = s?.toString()?.trim() ?: ""
                loadData()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Grid/List toggle
        binding.btnToggleView.setOnClickListener {
            isGrid = !isGrid
            fileAdapter.isGridMode = isGrid
            updateLayoutManager()
            fileAdapter.notifyDataSetChanged()
        }

        // Sort Dialog
        binding.btnSort.setOnClickListener {
            showSortDialog()
        }

        // Category Filter Chips
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCategory = when {
                checkedIds.contains(R.id.chipPhotos) -> FileCategory.PHOTOS
                checkedIds.contains(R.id.chipVideos) -> FileCategory.VIDEOS
                checkedIds.contains(R.id.chipPdfs) -> FileCategory.PDFS
                checkedIds.contains(R.id.chipDocs) -> FileCategory.DOCS
                checkedIds.contains(R.id.chipAudio) -> FileCategory.AUDIO
                else -> FileCategory.ALL
            }
            loadData()
        }
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.sort_date),
            getString(R.string.sort_name),
            getString(R.string.sort_size)
        )
        AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle(R.string.action_sort)
            .setItems(options) { _, which ->
                currentSort = when (which) {
                    1 -> "name_asc"
                    2 -> "size_desc"
                    else -> "date_desc"
                }
                loadData()
            }
            .show()
    }

    fun loadData() {
        val db = VaultXApplication.instance.databaseHelper
        val recents = db.getRecentFiles(8)
        val files = db.getAllFiles(currentCategory, currentSearch, currentSort)

        recentAdapter.updateData(recents)
        fileAdapter.updateData(files)

        if (recents.isEmpty()) {
            binding.tvRecentHeader.visibility = View.GONE
            binding.rvRecentFiles.visibility = View.GONE
        } else {
            binding.tvRecentHeader.visibility = View.VISIBLE
            binding.rvRecentFiles.visibility = View.VISIBLE
        }

        if (files.isEmpty()) {
            binding.layoutEmptyFiles.visibility = View.VISIBLE
            binding.rvFiles.visibility = View.GONE
        } else {
            binding.layoutEmptyFiles.visibility = View.GONE
            binding.rvFiles.visibility = View.VISIBLE
        }
    }

    private fun openFile(file: FileItem) {
        val local = file.localUri?.removePrefix("file://")?.let { java.io.File(it) }?.takeIf { it.exists() }
            ?: com.vaultx.app.ui.adapters.FileAdapter.findLocalVaultFile(requireContext(), file.name)
        if (local != null) {
            file.localUri = local.absolutePath
            launchViewerForFile(file)
            return
        }

        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("Downloading from Cloud")
            .setMessage("Fetching ${file.name} for viewing…")
            .setCancelable(false)
            .create()
        progressDialog.show()

        VaultXApplication.instance.telegramClient.downloadFileFromTelegram(file) { downloadedFile ->
            progressDialog.dismiss()
            if (downloadedFile != null && downloadedFile.exists()) {
                file.localUri = downloadedFile.absolutePath
                loadData()
                launchViewerForFile(file)
            } else {
                Toast.makeText(requireContext(), "Could not download file from Telegram", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchViewerForFile(file: FileItem) {
        val isPhoto = file.category == FileCategory.PHOTOS || com.vaultx.app.ui.adapters.FileAdapter.isImage(file.name)
        val isVideo = file.category == FileCategory.VIDEOS || com.vaultx.app.ui.adapters.FileAdapter.isVideo(file.name)
        val isPdf = file.category == FileCategory.PDFS || file.name.lowercase().endsWith(".pdf")

        when {
            isPhoto -> {
                val intent = Intent(requireContext(), PhotoViewerActivity::class.java).apply {
                    putExtra("file_name", file.name)
                    putExtra("file_size", file.formattedSize)
                    putExtra("file_id", file.id)
                    putExtra("file_uri", file.localUri)
                    putExtra("is_fav", file.isFavorite)
                }
                startActivity(intent)
            }
            isVideo -> {
                val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                    putExtra("file_name", file.name)
                    putExtra("file_id", file.id)
                    putExtra("file_uri", file.localUri)
                }
                startActivity(intent)
            }
            isPdf -> {
                val intent = Intent(requireContext(), com.vaultx.app.ui.viewer.PdfViewerActivity::class.java).apply {
                    putExtra("file_name", file.name)
                    putExtra("file_id", file.id)
                    putExtra("file_uri", file.localUri)
                }
                startActivity(intent)
            }
            else -> {
                openExternalDocument(file)
            }
        }
    }

    private fun openExternalDocument(file: FileItem) {
        val uriStr = file.localUri ?: return
        val localFile = java.io.File(uriStr)
        if (!localFile.exists()) {
            Toast.makeText(requireContext(), "File not found locally", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                localFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, file.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No app found to open ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private val syncListener: () -> Unit = {
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
        VaultXApplication.instance.telegramClient.addSyncListener(syncListener)
    }

    override fun onPause() {
        super.onPause()
        VaultXApplication.instance.telegramClient.removeSyncListener(syncListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
