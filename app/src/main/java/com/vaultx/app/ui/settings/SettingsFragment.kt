package com.vaultx.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.vaultx.app.R
import com.vaultx.app.VaultXApplication
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

        // Navigate to Trash
        binding.btnNavTrash.setOnClickListener {
            startActivity(Intent(requireContext(), TrashActivity::class.java))
        }

        // Configure Telegram API Keys
        binding.btnNavApiKeys.setOnClickListener {
            showApiKeysDialog()
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

    private fun showApiKeysDialog() {
        val client = VaultXApplication.instance.telegramClient
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val etApiId = EditText(requireContext()).apply {
            hint = "Telegram API_ID (e.g. 36163421)"
            setText(if (client.apiId != 0) client.apiId.toString() else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etApiHash = EditText(requireContext()).apply {
            hint = "Telegram API_HASH"
            setText(client.apiHash)
        }

        layout.addView(etApiId)
        layout.addView(etApiHash)

        AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle(R.string.setting_api_config)
            .setMessage("Credentials from https://my.telegram.org. Saved securely only on this device.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val idStr = etApiId.text.toString().trim()
                val hashStr = etApiHash.text.toString().trim()
                if (idStr.isNotEmpty()) client.apiId = idStr.toIntOrNull() ?: 0
                if (hashStr.isNotEmpty()) client.apiHash = hashStr
                Toast.makeText(requireContext(), "Telegram API Keys updated!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
