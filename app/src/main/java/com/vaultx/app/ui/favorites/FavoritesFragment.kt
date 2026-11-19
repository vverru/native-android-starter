package com.vaultx.app.ui.favorites

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.databinding.FragmentFavoritesBinding
import com.vaultx.app.ui.adapters.FileAdapter
import com.vaultx.app.ui.viewer.PhotoViewerActivity
import com.vaultx.app.ui.viewer.VideoPlayerActivity

class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private lateinit var fileAdapter: FileAdapter
    private var currentCategory = FileCategory.ALL

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fileAdapter = FileAdapter(
            files = emptyList(),
            isGridMode = true,
            onItemClick = { file -> openFile(file) },
            onFavoriteToggle = { file ->
                val position = fileAdapter.getFiles().indexOfFirst { it.id == file.id }
                if (position != -1) {
                    fileAdapter.removeFileAt(position)
                    if (fileAdapter.itemCount == 0) {
                        binding.layoutEmptyFav.visibility = View.VISIBLE
                        binding.rvFavorites.visibility = View.GONE
                    }
                }
                Thread {
                    VaultXApplication.instance.databaseHelper.setFavorite(file.id, false)
                }.start()
                Toast.makeText(requireContext(), "${file.name} ${getString(R.string.action_unfavorite)}", Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvFavorites.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvFavorites.adapter = fileAdapter

        binding.chipGroupFavFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCategory = when {
                checkedIds.contains(R.id.chipFavPhotos) -> FileCategory.PHOTOS
                checkedIds.contains(R.id.chipFavVideos) -> FileCategory.VIDEOS
                checkedIds.contains(R.id.chipFavPdfs) -> FileCategory.PDFS
                checkedIds.contains(R.id.chipFavDocs) -> FileCategory.DOCS
                else -> FileCategory.ALL
            }
            loadFavorites()
        }

        loadFavorites()
    }

    private fun loadFavorites() {
        val db = VaultXApplication.instance.databaseHelper
        val favs = db.getFavorites(currentCategory)
        fileAdapter.updateData(favs)

        if (favs.isEmpty()) {
            binding.layoutEmptyFav.visibility = View.VISIBLE
            binding.rvFavorites.visibility = View.GONE
        } else {
            binding.layoutEmptyFav.visibility = View.GONE
            binding.rvFavorites.visibility = View.VISIBLE
        }
    }

    private fun openFile(file: FileItem) {
        val isLocalAvailable = !file.localUri.isNullOrBlank() && java.io.File(file.localUri!!).exists()
        if (isLocalAvailable) {
            launchViewerForFile(file)
        } else {
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
                    loadFavorites()
                    launchViewerForFile(file)
                } else {
                    Toast.makeText(requireContext(), "Could not download file from Telegram", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun launchViewerForFile(file: FileItem) {
        when (file.category) {
            FileCategory.PHOTOS -> {
                val intent = Intent(requireContext(), PhotoViewerActivity::class.java).apply {
                    putExtra("file_name", file.name)
                    putExtra("file_size", file.formattedSize)
                    putExtra("file_id", file.id)
                    putExtra("file_uri", file.localUri)
                    putExtra("is_fav", true)
                }
                startActivity(intent)
            }
            FileCategory.VIDEOS -> {
                val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                    putExtra("file_name", file.name)
                    putExtra("file_id", file.id)
                    putExtra("file_uri", file.localUri)
                }
                startActivity(intent)
            }
            FileCategory.PDFS -> {
                val intent = Intent(requireContext(), com.vaultx.app.ui.viewer.PdfViewerActivity::class.java).apply {
                    putExtra("file_name", file.name)
                    putExtra("file_id", file.id)
                    putExtra("file_uri", file.localUri)
                }
                startActivity(intent)
            }
            else -> {
                if (file.name.lowercase().endsWith(".pdf")) {
                    val intent = Intent(requireContext(), com.vaultx.app.ui.viewer.PdfViewerActivity::class.java).apply {
                        putExtra("file_name", file.name)
                        putExtra("file_id", file.id)
                        putExtra("file_uri", file.localUri)
                    }
                    startActivity(intent)
                } else {
                    openExternalDocument(file)
                }
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

    private val syncListener = {
        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
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
