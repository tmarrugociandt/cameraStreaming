package com.ciandt.camerastreaming

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

class MainActivityYoutube : AppCompatActivity(), ConnectChecker {

    private lateinit var rtmpCamera2: RtmpCamera2
    private lateinit var openGlView: OpenGlView

    // 🔴 REPLACE WITH YOUR REAL YOUTUBE STREAM KEY
    // Mutable stream key so it can be provided at runtime via dialog
    private var streamKey: String = "PUT_YOUR_STREAM_KEY_HERE"

    // rtmp URL is computed from current streamKey
    private fun getRtmpUrl(): String = "rtmps://a.rtmps.youtube.com/live2/$streamKey"

    // 🎥 Recommended YouTube configuration: 720p
    private val width = 1280
    private val height = 720
    private val fps = 30
    private val videoBitrate = 2500 * 1000 // 2.5 Mbps
    private val audioBitrate = 128 * 1000
    private val sampleRate = 44100
    private val isStereo = true

    // 🔁 RETRY POLICY
    private val maxStartAttempts = 10
    private var startAttempts = 0
    private val startRetryDelayMs = 300L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.openGlView)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        rtmpCamera2 = RtmpCamera2(openGlView, this)

        // If there is no stream key configured, prompt for it on start
        showStreamKeyDialogIfNeeded()

        startButton.setOnClickListener {
            if (!rtmpCamera2.isStreaming) {
                if (checkPermissions()) {
                    // Before starting the stream, ensure a stream key is present
                    if (streamKey.isBlank() || streamKey == "PUT_YOUR_STREAM_KEY_HERE") {
                        showStreamKeyDialog()
                        Toast.makeText(this, "Please enter the YouTube stream key before starting.", Toast.LENGTH_LONG).show()
                    } else {
                        startStream()
                    }
                } else {
                    requestPermissions()
                }
            } else {
                Toast.makeText(this, "Already streaming YouTube", Toast.LENGTH_SHORT).show()
            }
        }

        stopButton.setOnClickListener {
            stopStream()
        }
    }

    private fun startStream() {
        val encoderRotation = getEncoderRotation()
        val (videoW, videoH) =
            if (encoderRotation == 90 || encoderRotation == 270)
                Pair(height, width)
            else
                Pair(width, height)

        val videoPrepared = rtmpCamera2.prepareVideo(
            videoW,
            videoH,
            fps,
            videoBitrate,
            encoderRotation,
            CameraHelper.Facing.BACK.ordinal
        )

        val audioPrepared = rtmpCamera2.prepareAudio(
            audioBitrate,
            sampleRate,
            isStereo
        )

        if (!videoPrepared || !audioPrepared) {
            Toast.makeText(this, "Error preparing YouTube stream", Toast.LENGTH_LONG).show()
            return
        }

        try {
            rtmpCamera2.startPreview()
        } catch (_: Exception) {}

        startAttempts = 0
        openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
    }

    private fun attemptStartStream() {
        openGlView.post {
            try {
                if (!rtmpCamera2.isStreaming) {
                    // Use the URL constructed with the provided streamKey
                    rtmpCamera2.startStream(getRtmpUrl())
                    Toast.makeText(this, "Starting YouTube Live...", Toast.LENGTH_SHORT).show()
                }
            } catch (_: IllegalStateException) {
                startAttempts++
                if (startAttempts <= maxStartAttempts) {
                    openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                } else {
                    Toast.makeText(this, "Failed to start YouTube stream", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Toast.makeText(this, "YouTube stream error: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Show dialog to enter stream key if it's empty or still the placeholder
    private fun showStreamKeyDialogIfNeeded() {
        if (streamKey.isBlank() || streamKey == "PUT_YOUR_STREAM_KEY_HERE") {
            showStreamKeyDialog()
        }
    }

    private fun showStreamKeyDialog() {
        val editText = EditText(this)
        editText.hint = "Stream key"
        if (streamKey != "PUT_YOUR_STREAM_KEY_HERE") editText.setText(streamKey)
        editText.inputType = InputType.TYPE_CLASS_TEXT 

        AlertDialog.Builder(this)
            .setTitle("YouTube Stream Key")
            .setMessage("Enter your YouTube stream key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                streamKey = editText.text.toString().trim()
                if (streamKey.isEmpty()) {
                    Toast.makeText(this, "Stream key is empty. Stream cannot be started.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Stream key saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopStream() {
        if (rtmpCamera2.isStreaming) {
            rtmpCamera2.stopStream()
        }
        try {
            rtmpCamera2.stopPreview()
        } catch (_: Exception) {}
        Toast.makeText(this, "YouTube stream stopped", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ),
            100
        )
    }

    private fun getEncoderRotation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return (90 - degrees + 360) % 360
    }

    // 🔌 CONNECT CHECKER CALLBACKS

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        runOnUiThread {
            Toast.makeText(this, "Connected to YouTube", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Connection failed: $reason", Toast.LENGTH_LONG).show()
            stopStream()
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            Toast.makeText(this, "Disconnected from YouTube", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAuthError() {
        runOnUiThread {
            Toast.makeText(this, "YouTube auth error", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAuthSuccess() {
        runOnUiThread {
            Toast.makeText(this, "YouTube auth success", Toast.LENGTH_SHORT).show()
        }
    }
}
