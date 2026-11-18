package com.vaultx.app.ui.viewer

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.vaultx.app.databinding.ActivityVideoPlayerBinding

@UnstableApi
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private var currentSpeed = 1.0f
    private var isLandscape = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fileName = intent.getStringExtra("file_name") ?: "Video Stream"
        binding.tvVideoTitle.text = fileName

        binding.btnVideoBack.setOnClickListener { finish() }

        // Orientation / Vertical & Horizontal Toggle
        binding.btnToggleOrientation.setOnClickListener {
            toggleOrientation()
        }

        binding.btnPlaybackSpeed.setOnClickListener {
            showSpeedDialog()
        }

        initializePlayer()
    }

    private fun toggleOrientation() {
        isLandscape = !isLandscape
        if (isLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            Toast.makeText(this, "Horizontal (16:9 Landscape)", Toast.LENGTH_SHORT).show()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            Toast.makeText(this, "Vertical (Portrait)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer

        val fileUri = intent.getStringExtra("file_uri")
        val videoUri = if (!fileUri.isNullOrBlank() && java.io.File(fileUri).exists()) {
            Uri.fromFile(java.io.File(fileUri))
        } else {
            Uri.parse("https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
        }

        val mediaItem = MediaItem.fromUri(videoUri)
        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
        val speedValues = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        AlertDialog.Builder(this, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                currentSpeed = speedValues[which]
                exoPlayer?.setPlaybackSpeed(currentSpeed)
                binding.btnPlaybackSpeed.text = "${currentSpeed}x"
                Toast.makeText(this, "Speed: ${currentSpeed}x", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}
