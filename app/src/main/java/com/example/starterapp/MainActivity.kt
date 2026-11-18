package com.example.starterapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.starterapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var clickCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnTap.setOnClickListener {
            clickCount++
            updateCounterDisplay()
            Toast.makeText(this, "Button tapped! Count: $clickCount", Toast.LENGTH_SHORT).show()
        }

        binding.btnReset.setOnClickListener {
            clickCount = 0
            updateCounterDisplay()
            Toast.makeText(this, "Counter reset!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCounterDisplay() {
        binding.tvCounter.text = getString(R.string.click_count_text, clickCount)
    }
}
