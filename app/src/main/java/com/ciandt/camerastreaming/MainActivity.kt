package com.ciandt.camerastreaming

import android.Manifest
import android.content.pm.PackageManager
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

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var rtmpCamera2: RtmpCamera2
    private lateinit var openGlView: OpenGlView

    private val rtmpUrl = "rtmp://18.130.36.142:1935/demo/live"

    private val width = 1280
    private val height = 720
    private val fps = 30
    private val videoBitrate = 1200 * 1000
    private val sampleRate = 44100
    private val isStereo = true
    private val audioBitrate = 128 * 1000

    // retry policy for startStream in case encoders need a moment after preview starts
    private val maxStartAttempts = 10
    private var startAttempts = 0
    private val startRetryDelayMs = 300L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.openGlView)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        // Do not override layoutParams at runtime — keep the XML params (avoids casting issues with different parent layouts)

        // Do not apply manual rotation; let OpenGlView/library handle preview orientation

        // Initialize rtmpCamera2 with OpenGlView and ConnectChecker
        rtmpCamera2 = RtmpCamera2(openGlView, this)

        startButton.setOnClickListener {
            if (!rtmpCamera2.isStreaming) {
                if (checkPermissions()) {
                    // Use encoder rotation mapping (device -> encoder) expected by the library
                    val encoderRotation = getDeviceRotationDegrees()
                    // swap width/height if encoder expects portrait orientation
                    val (videoW, videoH) = if (encoderRotation == 90 || encoderRotation == 270) Pair(height, width) else Pair(width, height)

                    val preparedVideo = rtmpCamera2.prepareVideo(videoW, videoH, fps, videoBitrate, encoderRotation, CameraHelper.Facing.BACK.ordinal)
                    val preparedAudio = rtmpCamera2.prepareAudio(audioBitrate, sampleRate, isStereo)

                    if (preparedVideo && preparedAudio) {
                        // start preview so encoders receive frames
                        try {
                            rtmpCamera2.startPreview()
                        } catch (_: Exception) {
                            // ignore: preview might already be started or surface not ready
                        }
                        // reset attempts and try to start stream with retries
                        startAttempts = 0
                        openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                    } else {
                        Toast.makeText(this, "Error preparing the stream", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    requestPermissions()
                }
            } else {
                Toast.makeText(this, "It's already streaming", Toast.LENGTH_SHORT).show()
            }
        }

        stopButton.setOnClickListener {
            if (rtmpCamera2.isStreaming) {
                rtmpCamera2.stopStream()
            }
            try {
                rtmpCamera2.stopPreview()
            } catch (_: Exception) { }
            Toast.makeText(this, "Transmission stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun attemptStartStream() {
        openGlView.post {
            try {
                if (!rtmpCamera2.isStreaming) {
                    rtmpCamera2.startStream(rtmpUrl)
                    Toast.makeText(this, "Starting transmission...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IllegalStateException) {
                // VideoEncoder not ready yet, retry a few times
                startAttempts++
                if (startAttempts <= maxStartAttempts) {
                    openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                } else {
                    Toast.makeText(this, "Failed to start stream: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Toast.makeText(this, "Failed to start stream: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // No manual rotation on resume; library manages preview orientation
    }

    private fun checkPermissions(): Boolean {
        val cameraPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val audioPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return cameraPermission == PackageManager.PERMISSION_GRANTED &&
                audioPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            100
        )
    }

    private fun getDeviceRotationDegrees(): Int {
        // Get rotation in a way that avoids calling the deprecated defaultDisplay on newer APIs
        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Activity.display is available and not deprecated on newer APIs
            this.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val displayDegrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (90 - displayDegrees + 360) % 360
    }

    // ConnectChecker methods

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        runOnUiThread { Toast.makeText(this, "Connection successful", Toast.LENGTH_SHORT).show() }
    }
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Connection failure: $reason", Toast.LENGTH_SHORT).show()
            rtmpCamera2.stopStream()
            rtmpCamera2.stopPreview()
        }
    }
    override fun onDisconnect() {
        runOnUiThread { Toast.makeText(this, "Offline", Toast.LENGTH_SHORT).show() }
    }
    override fun onAuthError() {
        runOnUiThread { Toast.makeText(this, "Authentication error", Toast.LENGTH_SHORT).show() }
    }
    override fun onAuthSuccess() {
        runOnUiThread { Toast.makeText(this, "Successful authentication", Toast.LENGTH_SHORT).show() }
    }
}
