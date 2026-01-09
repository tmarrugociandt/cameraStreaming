package com.ciandt.camerastreaming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var rtmpCamera2: RtmpCamera2
    private lateinit var openGlView: OpenGlView

    private lateinit var networkStatusText: TextView
    private lateinit var bitrateText: TextView

    private val rtmpUrl = "rtmp://18.130.36.142:1935/demo/live"

    private var width = 1280
    private var height = 720
    private var fps = 30
    private var videoBitrate = 1200 * 1000
    private val sampleRate = 44100
    private val isStereo = true
    private val audioBitrate = 128 * 1000

    // retry policy for startStream in case encoders need a moment after preview starts
    private val maxStartAttempts = 10
    private var startAttempts = 0
    private val startRetryDelayMs = 300L

    // reconnection control
    private var reconnectionJob: Job? = null
    private var reconnectionAttempts = 0
    private val maxReconnectionAttempts = 10
    private val reconnectionBaseDelayMs = 3000L

    // Network monitoring
    private val monitorIntervalMs = 4000L
    private val uiHandler = Handler(Looper.getMainLooper())
    private var monitorJob: Job? = null

    // adaptation thresholds (bytes/s)
    private val thresholdExcellent = 5_000_000L    // > ~5 Mbps
    private val thresholdGood = 2_500_000L         // > ~2.5 Mbps
    private val thresholdPoor = 800_000L          // ~0.8 Mbps

    // hysteresis to avoid flapping
    private var lastAdaptationTime = 0L
    private val adaptationCooldownMs = 5000L

    private var streamingSince: Long = 0L
    private var fallbackAttempted: Boolean = false

    // network change handling
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkLost: Boolean = false
    private var hadStreamingBeforeNetworkLoss: Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.openGlView)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        networkStatusText = findViewById(R.id.networkStatusText)
        bitrateText = findViewById(R.id.bitrateText)

        // Do not override layoutParams at runtime — keep the XML params (avoids casting issues with different parent layouts)

        // Do not apply manual rotation; let OpenGlView/library handle preview orientation

        // Initialize rtmpCamera2 with OpenGlView and ConnectChecker
        rtmpCamera2 = RtmpCamera2(openGlView, this)

        // register network callback to handle wifi <-> mobile transitions
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()

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
                        // start network monitor
                        startNetworkMonitor()
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
            // stop monitor
            stopNetworkMonitor()
            Toast.makeText(this, "Transmission stopped.", Toast.LENGTH_SHORT).show()
        }

        // initialize bitrate indicator
        bitrateText.text = getString(R.string.bitrate_label)

    }

    private fun attemptStartStream() {
        openGlView.post {
            try {
                if (!rtmpCamera2.isStreaming) {
                    Log.d("MainActivity", "Calling rtmpCamera2.startStream with URL=${rtmpUrl}")
                    rtmpCamera2.startStream(rtmpUrl)
                    val nowStreaming = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                    Log.d("MainActivity", "startStream() returned, isStreaming=$nowStreaming")
                    Toast.makeText(this, "Starting transmission... isStreaming=$nowStreaming", Toast.LENGTH_SHORT).show()
                    if (nowStreaming) {
                        streamingSince = System.currentTimeMillis()
                        fallbackAttempted = false
                    } else {
                        startAttempts++
                        if (startAttempts <= maxStartAttempts) {
                            openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                        } else {
                            startReconnectionRetries()
                        }
                    }

                    uiHandler.postDelayed({
                        val s = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                        if (!s) {
                            Toast.makeText(this, "WARNING: the stream did not start (isStreaming=false). Attempting fallback if applicable...", Toast.LENGTH_LONG).show()
                            if (!fallbackAttempted) {
                                // attempt a non-SSL rtmp fallback if possible
                                val alt = rtmpUrl.replaceFirst("rtmps://", "rtmp://")
                                if (alt != rtmpUrl) {
                                    try {
                                        fallbackAttempted = true
                                        rtmpCamera2.startStream(alt)
                                    } catch (ex: Throwable) {
                                        Log.e("MainActivity", "Fallback startStream failed: ${ex.message}")
                                    }
                                    uiHandler.postDelayed({
                                        val s2 = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                                        if (!s2) {
                                            Toast.makeText(this, "Fallback failed - the stream did not start. Check logs/ConnectChecker.", Toast.LENGTH_LONG).show()
                                        } else {
                                            streamingSince = System.currentTimeMillis()
                                        }
                                    }, 3000)
                                }
                            }
                        }
                    }, 3000)

                } else {
                    streamingSince = streamingSince.takeIf { it != 0L } ?: System.currentTimeMillis()
                }
            } catch (e: IllegalStateException) {
                startAttempts++
                if (startAttempts <= maxStartAttempts) {
                    openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                } else {
                    startReconnectionRetries()
                }
            } catch (t: Throwable) {
                Toast.makeText(this, "Failed to start stream: ${t.message}", Toast.LENGTH_LONG).show()
                startAttempts++
                if (startAttempts <= maxStartAttempts) {
                    openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                } else {
                    startReconnectionRetries()
                }
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

    // ------------------ Network monitor & adaptation ------------------

    private fun startNetworkMonitor() {
        stopNetworkMonitor()
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            var lastTx = TrafficStats.getTotalTxBytes()
            while (isActive) {
                val start = System.currentTimeMillis()
                delay(monitorIntervalMs)
                val end = System.currentTimeMillis()
                val nowTx = TrafficStats.getTotalTxBytes()
                val bytes = if (lastTx <= 0L) 0L else nowTx - lastTx
                lastTx = nowTx
                val durationSec = (end - start) / 1000.0
                val throughput = if (durationSec > 0) (bytes / durationSec).roundToInt() else 0

                val rttMs = measureRttMs("8.8.8.8", 53, 1000)
                val loss = if (throughput == 0 && rttMs >= 1000) 1.0 else 0.0

                val streamingState = try { if (this@MainActivity::rtmpCamera2.isInitialized) rtmpCamera2.isStreaming else false } catch (_: Exception) { false }

                Log.d("NetMonitor", "throughput=$throughput B/s rtt=${rttMs}ms loss=$loss isStreaming=$streamingState streamingSince=$streamingSince")

                uiHandler.post {
                    updateNetworkUiAndAdapt(throughput.toLong(), rttMs, loss)
                }
            }
        }
    }

    private fun stopNetworkMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun measureRttMs(host: String, port: Int, timeoutMs: Int): Long {
        try {
            Socket().use { s ->
                val start = System.currentTimeMillis()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                val end = System.currentTimeMillis()
                return end - start
            }
        } catch (t: Throwable) {
            return timeoutMs.toLong()
        }
    }

    private fun updateNetworkUiAndAdapt(throughputBps: Long, rttMs: Long, packetLoss: Double) {
        val readable = when {
            throughputBps >= thresholdExcellent -> "Excellent"
            throughputBps >= thresholdGood -> "Good"
            throughputBps >= thresholdPoor -> "Fair"
            else -> "Poor"
        }
        networkStatusText.text = "Network: $readable (rtt=${rttMs}ms)"
        when (readable) {
            "Excellent" -> networkStatusText.setBackgroundColor(Color.parseColor("#8800AA00"))
            "Good" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FFD700"))
            "Fair" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FF8C00"))
            "Poor" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FF0000"))
            else -> networkStatusText.setBackgroundColor(Color.parseColor("#66000000"))
        }
        bitrateText.text = "Bitrate: ${videoBitrate / 1000} kbps"

        val now = System.currentTimeMillis()
        if (streamingSince == 0L || now - streamingSince < 8000L) return
        if (now - lastAdaptationTime < adaptationCooldownMs) return

        when (readable) {
            "Excellent" -> attemptAdaptation(3500 * 1000, width, height, 30)
            "Good" -> attemptAdaptation(2000 * 1000, width, height, 30)
            "Fair" -> attemptAdaptation(1000 * 1000, 960, 540, 24)
            "Poor" -> attemptAdaptation(500 * 1000, 640, 360, 15)
        }
    }

    private fun attemptAdaptation(targetBitrate: Int, targetW: Int, targetH: Int, targetFps: Int) {
        if (kotlin.math.abs(videoBitrate - targetBitrate) < 200 * 1000 && width == targetW && height == targetH && fps == targetFps) return

        lastAdaptationTime = System.currentTimeMillis()
        Log.i("Adaptation", "Adapting to bitrate=$targetBitrate, ${targetW}x${targetH}@${targetFps}")

        val oldBitrate = videoBitrate
        val oldWidth = width
        val oldHeight = height
        val oldFps = fps

        val successSetOnFly = trySetBitrateOnFly(targetBitrate)

        if (successSetOnFly) {
            videoBitrate = targetBitrate
            bitrateText.text = "Bitrate: ${videoBitrate / 1000} kbps"
            showAdaptationToast("Adaptation: bitrate ${videoBitrate / 1000} kbps")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val wasStreaming = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }

            try { rtmpCamera2.stopPreview() } catch (_: Exception) {}

            width = targetW
            height = targetH
            fps = targetFps

            val encoderRotation = getDeviceRotationDegrees()
            val (videoW, videoH) = if (encoderRotation == 90 || encoderRotation == 270) Pair(height, width) else Pair(width, height)

            val prepared = try {
                rtmpCamera2.prepareVideo(videoW, videoH, fps, targetBitrate, encoderRotation, CameraHelper.Facing.BACK.ordinal)
            } catch (t: Throwable) {
                Log.w("Adaptation", "prepareVideo threw: ${t.message}")
                false
            }

            if (!prepared) {
                Log.w("Adaptation", "Could not prepare video with new configuration, reverting to previous settings")
                showAdaptationToast("Adaptation failed: reverting to previous settings")

                width = oldWidth
                height = oldHeight
                fps = oldFps
                try {
                    val prevEncoderRotation = getDeviceRotationDegrees()
                    val (prevW, prevH) = if (prevEncoderRotation == 90 || prevEncoderRotation == 270) Pair(height, width) else Pair(width, height)
                    rtmpCamera2.prepareVideo(prevW, prevH, fps, oldBitrate, prevEncoderRotation, CameraHelper.Facing.BACK.ordinal)
                } catch (t: Throwable) {
                    Log.w("Adaptation", "Re-prepare previous settings threw: ${t.message}")
                }

                try { rtmpCamera2.startPreview() } catch (_: Exception) {}

                if (wasStreaming) {
                    try {
                        if (!tryIsStreaming()) {
                            rtmpCamera2.startStream(rtmpUrl)
                        }
                    } catch (t: Throwable) {
                        Log.e("Adaptation", "Error restarting stream after failed adaptation: ${t.message}")
                    }
                }

                return@launch
            }

            videoBitrate = targetBitrate
            bitrateText.text = "Bitrate: ${videoBitrate / 1000} kbps"

            try { rtmpCamera2.startPreview() } catch (_: Exception) {}

            if (wasStreaming) {
                try { if (!tryIsStreaming()) rtmpCamera2.startStream(rtmpUrl) } catch (t: Throwable) { Log.e("Adaptation", "Error re-starting stream after adaptation: ${t.message}") }
            }

            showAdaptationToast("Adaptation applied: ${videoBitrate / 1000} kbps, ${width}x${height}@${fps}")
        }
    }

    private fun trySetBitrateOnFly(targetBitrate: Int): Boolean {
        return try {
            val cls = rtmpCamera2::class.java
            val method = try { cls.getMethod("setVideoBitrateOnFly", Int::class.javaPrimitiveType) } catch (e: NoSuchMethodException) { null }
            if (method != null) {
                method.invoke(rtmpCamera2, targetBitrate)
                Log.i("Adaptation", "setVideoBitrateOnFly invoked")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.w("Adaptation", "On-fly bitrate change failed: ${t.message}")
            false
        }
    }

    private fun showAdaptationToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // reconnection loop
    private fun startReconnectionRetries() {
        if (reconnectionJob?.isActive == true) return
        reconnectionJob = CoroutineScope(Dispatchers.Main).launch {
            reconnectionAttempts = 0
            while (isActive && reconnectionAttempts < maxReconnectionAttempts && !tryIsStreaming()) {
                val delayMs = reconnectionBaseDelayMs * (reconnectionAttempts + 1)
                Log.i("MainActivity", "Reconnection attempt ${reconnectionAttempts + 1}, waiting ${delayMs}ms")
                delay(delayMs)
                try { attemptStartStream() } catch (t: Throwable) { Log.w("MainActivity", "Reconnection attempt failed: ${t.message}") }
                reconnectionAttempts++
            }
            if (tryIsStreaming()) {
                Log.i("MainActivity", "Reconnected after ${reconnectionAttempts} attempts")
                Toast.makeText(this@MainActivity, "Reconnected", Toast.LENGTH_SHORT).show()
                streamingSince = System.currentTimeMillis()
            } else {
                Log.w("MainActivity", "Could not reconnect after $reconnectionAttempts attempts")
                uiHandler.post { Toast.makeText(this@MainActivity, "Could not reconnect. The app will keep trying in background.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun tryIsStreaming(): Boolean {
        return try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
    }

    // ConnectChecker methods

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        runOnUiThread {
            Toast.makeText(this, "Connection successful", Toast.LENGTH_SHORT).show()
            streamingSince = System.currentTimeMillis()
            networkStatusText.text = getString(R.string.network_connected)
            networkStatusText.setBackgroundColor(Color.parseColor("#8800AA00"))
        }
    }
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Connection failure: $reason", Toast.LENGTH_SHORT).show()
            rtmpCamera2.stopStream()
            rtmpCamera2.stopPreview()

            // mark disconnected time
            streamingSince = 0L
            networkStatusText.text = "Connection: failed"
            networkStatusText.setBackgroundColor(Color.parseColor("#88FF0000"))
            try {
                attemptAdaptation(300 * 1000, 480, 272, 12)
            } catch (t: Throwable) {
                Log.w("MainActivity", "Degraded adaptation failed: ${t.message}")
            }

            startReconnectionRetries()
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

    override fun onDestroy() {
        super.onDestroy()
        stopNetworkMonitor()
        unregisterNetworkCallback()
        try {
            if (rtmpCamera2.isStreaming) {
                rtmpCamera2.stopStream()
            }
        } catch (e: UninitializedPropertyAccessException) {
        } catch (t: Throwable) {
            Log.w("MainActivity", "Error stopping stream in onDestroy: ${t.message}")
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                super.onLost(network)
                uiHandler.post {
                    Log.i("NetworkCallback", "Network lost")
                    networkLost = true
                    hadStreamingBeforeNetworkLoss = tryIsStreaming()
                    networkStatusText.text = "Network: disconnected"
                    try { networkStatusText.setBackgroundColor(Color.parseColor("#88FF0000")) } catch (_: Exception) {}
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connectivityManager.bindProcessToNetwork(null)
                            Log.i("NetworkCallback", "Process unbound from lost network")
                        }
                    } catch (t: Throwable) {
                        Log.w("NetworkCallback", "unbind process network failed: ${t.message}")
                    }
                }
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                uiHandler.post {
                    Log.i("NetworkCallback", "Network available")
                    networkLost = false
                    networkStatusText.text = getString(R.string.network_connected)
                    try { networkStatusText.setBackgroundColor(Color.parseColor("#8800AA00")) } catch (_: Exception) {}
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connectivityManager.bindProcessToNetwork(network)
                            Log.i("NetworkCallback", "Process bound to new network")
                        }
                    } catch (t: Throwable) {
                        Log.w("NetworkCallback", "bind process network failed: ${t.message}")
                    }
                    if (hadStreamingBeforeNetworkLoss || startAttempts > 0) {
                        try {
                            if (hadStreamingBeforeNetworkLoss) {
                                try {
                                    if (rtmpCamera2.isStreaming) {
                                        rtmpCamera2.stopStream()
                                    }
                                } catch (t: Throwable) {
                                    Log.w("NetworkCallback", "Stopping RTMP stream failed: ${t.message}")
                                }
                            }
                            uiHandler.postDelayed({ try { attemptStartStream() } catch (t: Throwable) { Log.w("NetworkCallback", "Delayed attemptStartStream failed: ${t.message}") } }, 700)
                        } catch (t: Throwable) {
                            Log.w("NetworkCallback", "Immediate restart flow failed: ${t.message}")
                        }
                        startReconnectionRetries()
                    }
                }
            }
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
        } catch (t: Throwable) {
            Log.w("NetworkCallback", "registerDefaultNetworkCallback failed: ${t.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (t: Throwable) {
            Log.w("NetworkCallback", "unregisterNetworkCallback failed: ${t.message}")
        }
        networkCallback = null
    }
}
