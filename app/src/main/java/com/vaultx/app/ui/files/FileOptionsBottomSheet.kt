package com.vaultx.app.ui.files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.databinding.DialogFileOptionsBinding
import com.vaultx.app.ui.adapters.FileAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogFileOptionsBinding? = null
    private val binding get() = _binding!!

    private var fileItem: FileItem? = null
    private var onOpenListener: ((FileItem) -> Unit)? = null
    private var onFavoriteToggleListener: ((FileItem) -> Unit)? = null
    private var onRenameListener: ((FileItem, String) -> Unit)? = null
    private var onDeleteListener: ((FileItem) -> Unit)? = null

    companion object {
        fun newInstance(
            file: FileItem,
            onOpen: (FileItem) -> Unit,
            onFavoriteToggle: (FileItem) -> Unit,
            onRename: (FileItem, String) -> Unit,
            onDelete: (FileItem) -> Unit
        ): FileOptionsBottomSheet {
            val sheet = FileOptionsBottomSheet()
            sheet.fileItem = file
            sheet.onOpenListener = onOpen
            sheet.onFavoriteToggleListener = onFavoriteToggle
            sheet.onRenameListener = onRename
            sheet.onDeleteListener = onDelete
            return sheet
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogFileOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val file = fileItem ?: run { dismiss(); return }

        // Set file details
        binding.tvSheetFileName.text = file.name
        val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(file.uploadTimestamp))
        binding.tvSheetFileMeta.text = "${file.formattedSize} • ${file.category.name} • $dateStr"

        // Load thumbnail preview
        val placeholderIcon = when (file.category) {
            FileCategory.PHOTOS -> R.drawable.ic_photo
            FileCategory.VIDEOS -> R.drawable.ic_video
            FileCategory.PDFS -> R.drawable.ic_pdf
            FileCategory.DOCS -> R.drawable.ic_doc
            FileCategory.AUDIO -> R.drawable.ic_audio
            FileCategory.ALL -> R.drawable.ic_folder
        }

        val previewFile = file.localUri?.let { File(it) }?.takeIf { it.exists() }
            ?: file.thumbnailUri?.let { File(it) }?.takeIf { it.exists() }
            ?: FileAdapter.findLocalVaultFile(requireContext(), file.name)

        if (previewFile != null && (file.category == FileCategory.PHOTOS || file.category == FileCategory.VIDEOS || FileAdapter.isImageOrVideo(file.name))) {
            Glide.with(this)
                .load(previewFile)
                .centerCrop()
                .placeholder(placeholderIcon)
                .error(placeholderIcon)
                .into(binding.ivSheetIcon)
        } else {
            binding.ivSheetIcon.setImageResource(placeholderIcon)
        }

        // Setup Favorite state
        updateFavoriteUi(file.isFavorite)

        // Action 1: Open
        binding.actionSheetOpen.setOnClickListener {
            dismiss()
            onOpenListener?.invoke(file)
        }

        // Action 2: Favorite Toggle
        binding.actionSheetFavorite.setOnClickListener {
            file.isFavorite = !file.isFavorite
            updateFavoriteUi(file.isFavorite)
            onFavoriteToggleListener?.invoke(file)
            val msg = if (file.isFavorite) getString(R.string.action_favorite) else getString(R.string.action_unfavorite)
            Toast.makeText(requireContext(), "${file.name} $msg", Toast.LENGTH_SHORT).show()
        }

        // Action 3: Rename
        binding.actionSheetRename.setOnClickListener {
            dismiss()
            showRenameDialog(file)
        }

        // Action 4: Share
        binding.actionSheetShare.setOnClickListener {
            dismiss()
            shareFile(file)
        }

        // Action 5: Delete from Telegram & Vault
        binding.actionSheetDelete.setOnClickListener {
            dismiss()
            confirmCloudDelete(file)
        }
    }

    private fun updateFavoriteUi(isFav: Boolean) {
        binding.ivSheetFavIcon.setImageResource(
            if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
        binding.tvSheetFavText.text = if (isFav) "Remove from Favorites" else "Add to Favorites"
    }

    private fun showRenameDialog(file: FileItem) {
        val input = EditText(requireContext()).apply {
            setText(file.name)
            setSelection(text.length)
            setTextColor(requireContext().getColor(R.color.vaultx_text_primary))
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(50, 20, 50, 0)
            addView(input)
        }

        AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("Rename File")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != file.name) {
                    onRenameListener?.invoke(file, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareFile(file: FileItem) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            val local = file.localUri?.let { File(it) }?.takeIf { it.exists() }
                ?: FileAdapter.findLocalVaultFile(requireContext(), file.name)
            if (local != null) {
                type = file.mimeType
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    local
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "VaultX File: ${file.name} (Telegram Cloud Storage)")
            }
        }
        startActivity(Intent.createChooser(shareIntent, "Share ${file.name}"))
    }

    private fun confirmCloudDelete(file: FileItem) {
        AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("Delete from Vault & Telegram?")
            .setMessage("Are you sure you want to delete '${file.name}'?\n\nThis will permanently delete this file from your device AND from your private Telegram Cloud channel.")
            .setPositiveButton("Delete Permanently") { _, _ ->
                VaultXApplication.instance.telegramClient.deleteFileFromCloudAndLocal(file) {
                    Toast.makeText(requireContext(), "Deleted from Vault & Telegram channel", Toast.LENGTH_SHORT).show()
                    onDeleteListener?.invoke(file)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
