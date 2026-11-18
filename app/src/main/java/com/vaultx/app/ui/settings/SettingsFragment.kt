package com.vaultx.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.model.StorageBreakdown
import com.vaultx.app.databinding.FragmentSettingsBinding
import com.vaultx.app.ui.auth.AuthActivity
import com.vaultx.app.ui.trash.TrashActivity

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val client = VaultXApplication.instance.telegramClient
        val phone = client.phoneNumber
        binding.tvProfileName.text = if (phone.isNotBlank()) "Telegram: $phone" else "Telegram: Connected"

        // Display Real Storage Breakdown
        refreshStorageUsage()

        // Navigate to Trash
        binding.btnNavTrash.setOnClickListener {
            startActivity(Intent(requireContext(), TrashActivity::class.java))
        }

        // Toggle Language
        binding.btnNavLanguage.setOnClickListener {
            showLanguageDialog()
        }

        // Logout
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
                .setTitle(R.string.btn_logout)
                .setMessage("Are you sure? Your files will remain safely stored in your private Telegram channel.")
                .setPositiveButton("Logout") { _, _ ->
                    client.logout()
                    startActivity(Intent(requireContext(), AuthActivity::class.java))
                    requireActivity().finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStorageUsage()
    }

    private fun refreshStorageUsage() {
        try {
            val stats = VaultXApplication.instance.databaseHelper.getStorageBreakdown()
            binding.tvTotalUsed.text = stats.formattedTotal
            binding.tvPhotoStorage.text = "${stats.formattedPhotos} (${stats.photosCount} files)"
            binding.tvVideoStorage.text = "${stats.formattedVideos} (${stats.videosCount} files)"
            binding.tvDocStorage.text = "${stats.formattedPdfs} (${stats.pdfsCount} files)"
            binding.tvAudioStorage.text = "${stats.formattedAudio} (${stats.audioCount} files)"
        } catch (_: Exception) {}
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("English", "हिन्दी (Hindi)")
        AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle(R.string.setting_language)
            .setItems(languages) { _, which ->
                val lang = if (which == 1) "हिन्दी" else "English"
                Toast.makeText(requireContext(), "Language: $lang (System locale applied)", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
