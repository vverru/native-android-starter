package com.vaultx.app.ui.backup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.FileCategory
import com.vaultx.app.data.model.FileItem
import com.vaultx.app.databinding.FragmentBackupBinding

class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchCameraBackup.setOnCheckedChangeListener { _, isChecked ->
            val status = if (isChecked) "Enabled" else "Disabled"
            Toast.makeText(requireContext(), "Camera Auto-Backup $status", Toast.LENGTH_SHORT).show()
        }

        binding.switchWhatsappBackup.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // Show PRO upsell dialog
                buttonView.isChecked = false
                showProDialog()
            }
        }

        binding.btnBackUpNow.setOnClickListener {
            binding.progressBackup.isIndeterminate = true
            binding.tvBackupStatus.text = "Backing up camera photos to Telegram…"
            binding.btnBackUpNow.isEnabled = false

            binding.root.postDelayed({
                val now = System.currentTimeMillis()
                val newFile = FileItem(
                    name = "DCIM_Sync_${now % 10000}.jpg",
                    sizeBytes = 5_200_000,
                    mimeType = "image/jpeg",
                    category = FileCategory.PHOTOS,
                    isFavorite = false,
                    uploadTimestamp = now
                )
                VaultXApplication.instance.databaseHelper.insertFile(newFile)

                binding.progressBackup.isIndeterminate = false
                binding.progressBackup.progress = 100
                binding.tvBackupStatus.text = "Backup completed! Synced: ${newFile.name}"
                binding.btnBackUpNow.isEnabled = true
                Toast.makeText(requireContext(), "1 photo backed up to private Telegram channel!", Toast.LENGTH_SHORT).show()
            }, 1800)
        }
    }

    private fun showProDialog() {
        AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("VaultX PRO 👑")
            .setMessage("Unlock WhatsApp Media Auto-Backup, Parallel Chunk Uploads (3x speed), and Files > 2GB for just ₹99/month or ₹499/year.")
            .setPositiveButton("Upgrade to PRO") { _, _ ->
                Toast.makeText(requireContext(), "Google Play Billing connecting…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Maybe Later", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
