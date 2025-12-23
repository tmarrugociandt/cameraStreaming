package com.ciandt.camerastreaming

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.Button
import android.widget.Toast
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
    private val streamKey = "PUT_YOUR_STREAM_KEY_HERE"

    // ✅ Official RTMPS URL for YouTube
    private val rtmpUrl = "rtmps://a.rtmps.youtube.com/live2/$streamKey"

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

        startButton.setOnClickListener {
            if (!rtmpCamera2.isStreaming) {
                if (checkPermissions()) {
                    startStream()
                } else {
                    requestPermissions()
                }
            } else {
                Toast.makeText(this, "Already streaming youtube", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Error preparing stream youtube", Toast.LENGTH_LONG).show()
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
                    rtmpCamera2.startStream(rtmpUrl)
                    Toast.makeText(this, "Starting YouTube Live...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IllegalStateException) {
                startAttempts++
                if (startAttempts <= maxStartAttempts) {
                    openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                } else {
                    Toast.makeText(this, "Failed to start stream youtube", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Toast.makeText(this, "Stream error youtube : ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopStream() {
        if (rtmpCamera2.isStreaming) {
            rtmpCamera2.stopStream()
        }
        try {
            rtmpCamera2.stopPreview()
        } catch (_: Exception) {}
        Toast.makeText(this, "Stream stopped youtube", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Connected to YouTube youtube", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Connection failed youtube: $reason", Toast.LENGTH_LONG).show()
            stopStream()
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            Toast.makeText(this, "Disconnected youtube", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAuthError() {
        runOnUiThread {
            Toast.makeText(this, "Auth error youtube", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAuthSuccess() {
        runOnUiThread {
            Toast.makeText(this, "Auth success youtube", Toast.LENGTH_SHORT).show()
        }
    }
}
