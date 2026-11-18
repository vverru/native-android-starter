package com.vaultx.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vaultx.app.VaultXApplication
import com.vaultx.app.databinding.ActivityAuthBinding
import com.vaultx.app.ui.MainActivity

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val client = VaultXApplication.instance.telegramClient

        binding.btnSendOtp.setOnClickListener {
            val phone = binding.etPhone.text?.toString()?.trim() ?: ""
            if (phone.length < 8) {
                Toast.makeText(this, "Please enter a valid phone number with country code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            client.requestPhoneOtp(phone)
            binding.layoutStepPhone.visibility = View.GONE
            binding.layoutStepOtp.visibility = View.VISIBLE
            Toast.makeText(this, "OTP code requested via Telegram MTProto!", Toast.LENGTH_LONG).show()
        }

        binding.btnVerifyOtp.setOnClickListener {
            val code = binding.etOtp.text?.toString()?.trim() ?: ""
            if (code.isBlank()) {
                Toast.makeText(this, "Please enter the verification code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val success = client.verifyCode(code)
            if (success) {
                Toast.makeText(this, "Telegram connected! Private vault ready.", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }
}
