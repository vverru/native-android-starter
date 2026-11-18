package com.vaultx.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vaultx.app.VaultXApplication
import com.vaultx.app.data.telegram.TelegramAuthState
import com.vaultx.app.databinding.ActivityAuthBinding
import com.vaultx.app.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val client = VaultXApplication.instance.telegramClient

        // Observe authorization state changes from TDLib
        lifecycleScope.launch {
            client.authState.collectLatest { state ->
                when (state) {
                    TelegramAuthState.WAIT_PHONE -> {
                        binding.progressAuth.visibility = View.GONE
                        binding.layoutStepPhone.visibility = View.VISIBLE
                        binding.layoutStepOtp.visibility = View.GONE
                        binding.layoutStep2Fa.visibility = View.GONE
                    }
                    TelegramAuthState.WAIT_CODE -> {
                        binding.progressAuth.visibility = View.GONE
                        binding.layoutStepPhone.visibility = View.GONE
                        binding.layoutStepOtp.visibility = View.VISIBLE
                        binding.layoutStep2Fa.visibility = View.GONE
                    }
                    TelegramAuthState.WAIT_2FA -> {
                        binding.progressAuth.visibility = View.GONE
                        binding.layoutStepPhone.visibility = View.GONE
                        binding.layoutStepOtp.visibility = View.GONE
                        binding.layoutStep2Fa.visibility = View.VISIBLE
                    }
                    TelegramAuthState.LOGGED_IN -> {
                        binding.progressAuth.visibility = View.GONE
                        Toast.makeText(this@AuthActivity, "Telegram Connected! Private Vault Active.", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@AuthActivity, MainActivity::class.java))
                        finish()
                    }
                }
            }
        }

        // Send OTP button
        binding.btnSendOtp.setOnClickListener {
            val phone = binding.etPhone.text?.toString()?.trim() ?: ""
            if (phone.length < 8) {
                Toast.makeText(this, "Enter a valid phone number with country code (e.g. +91XXXXXXXXXX)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.progressAuth.visibility = View.VISIBLE
            binding.btnSendOtp.isEnabled = false

            client.requestPhoneOtp(phone) { success, error ->
                binding.btnSendOtp.isEnabled = true
                binding.progressAuth.visibility = View.GONE
                if (success) {
                    Toast.makeText(this, "Code sent via Telegram MTProto!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Telegram Error: ${error ?: "Failed to send code"}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Verify OTP button
        binding.btnVerifyOtp.setOnClickListener {
            val code = binding.etOtp.text?.toString()?.trim() ?: ""
            if (code.isBlank()) {
                Toast.makeText(this, "Enter the verification code received on Telegram", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.progressAuth.visibility = View.VISIBLE
            binding.btnVerifyOtp.isEnabled = false

            client.verifyCode(code) { success, error ->
                binding.btnVerifyOtp.isEnabled = true
                binding.progressAuth.visibility = View.GONE
                if (!success) {
                    Toast.makeText(this, "Telegram Error: ${error ?: "Invalid code"}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Verify 2FA Password button
        binding.btnVerify2Fa.setOnClickListener {
            val password = binding.et2FaPassword.text?.toString() ?: ""
            if (password.isBlank()) {
                Toast.makeText(this, "Enter your 2FA password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.progressAuth.visibility = View.VISIBLE
            binding.btnVerify2Fa.isEnabled = false

            client.verify2FaPassword(password) { success, error ->
                binding.btnVerify2Fa.isEnabled = true
                binding.progressAuth.visibility = View.GONE
                if (!success) {
                    Toast.makeText(this, "Telegram Error: ${error ?: "Incorrect 2FA password"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
