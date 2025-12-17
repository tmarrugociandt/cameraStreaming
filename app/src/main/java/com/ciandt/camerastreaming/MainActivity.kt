package com.ciandt.camerastreaming

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.ciandt.camerastreaming.R
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
    private val rotation = 90

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.openGlView)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        // Inicializar rtmpCamera2 con OpenGlView y ConnectChecker
        rtmpCamera2 = RtmpCamera2(openGlView, this)

        startButton.setOnClickListener {
            if (!rtmpCamera2.isStreaming) {
                if (checkPermissions()) {
                    val preparedVideo = rtmpCamera2.prepareVideo(width, height, fps, videoBitrate, rotation, CameraHelper.Facing.BACK.ordinal)
                    val preparedAudio = rtmpCamera2.prepareAudio(audioBitrate, sampleRate, isStereo)

                    if (preparedVideo && preparedAudio) {
                        rtmpCamera2.startStream(rtmpUrl)
                        Toast.makeText(this, "Starting transmission...", Toast.LENGTH_SHORT).show()
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
                rtmpCamera2.stopPreview()
                Toast.makeText(this, "Transmission stopped.", Toast.LENGTH_SHORT).show()
            }
        }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permits granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permits denied.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Métodos ConnectChecker

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        runOnUiThread { Toast.makeText(this, "Conexión exitosa", Toast.LENGTH_SHORT).show() }
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
