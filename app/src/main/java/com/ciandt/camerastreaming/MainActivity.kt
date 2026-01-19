package com.ciandt.camerastreaming

import android.Manifest
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var rtmpCamera2: RtmpCamera2
    private lateinit var openGlView: OpenGlView

    private lateinit var networkStatusText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var streamingTimerText: TextView
    private lateinit var lagIndicatorText: TextView

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

    private var timerJob: Job? = null
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
        streamingTimerText = findViewById(R.id.streamingTimerText)
        lagIndicatorText = findViewById(R.id.lagIndicatorText)

        // Initialize rtmpCamera2 with OpenGlView and ConnectChecker
        rtmpCamera2 = RtmpCamera2(openGlView, this)

        // register network callback to handle wifi <-> mobile transitions
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()

        startButton.setOnClickListener {
            if (!rtmpCamera2.isStreaming) {
                if (checkPermissions()) {
                    startStream()
                } else {
                    requestPermissions()
                }
            } else {
                Toast.makeText(this, "It's already streaming", Toast.LENGTH_SHORT).show()
            }
        }

        stopButton.setOnClickListener {
            stopStream()
        }

        // initialize bitrate indicator
        bitrateText.text = getString(R.string.bitrate_label)
    }

    private fun startStream() {
        // First, check network quality and adapt bitrate BEFORE starting
        Log.d("MainActivity", "Checking network quality before stream start...")
        checkNetworkAndAdaptBitrate()

        // First try: use the actual measured preview size as encoder resolution so
        // the encoder receives frames with the same pixel dimensions that the
        // user sees (avoids letterboxing/pillarboxing). Adjust for encoder rotation.
        val viewW = openGlView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val viewH = openGlView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        // Try multiple encoder rotations and pick the one that accepts a view-based
        // resolution (or scaled fallback). This avoids inverted rotation problems
        // where vertical becomes horizontal on the receiving end.
        fun even(v: Int) = if (v % 2 == 0) v else v - 1

        val rotationCandidates = listOf(0, 90, 270, 180)
        var chosenRotation: Int? = null
        var videoPrepared = false

        val maxSide = 1920

        for (rot in rotationCandidates) {
            // compute encoder dimensions this rotation would require for the view
            var candW = if (rot == 90 || rot == 270) viewH else viewW
            var candH = if (rot == 90 || rot == 270) viewW else viewH
            candW = even(candW)
            candH = even(candH)

            try {
                Log.d("MainActivity", "Trying prepareVideo with rotation $rot and view size ${candW}x${candH}")
                if (rtmpCamera2.prepareVideo(candW, candH, fps, videoBitrate, rot, CameraHelper.Facing.BACK.ordinal)) {
                    videoPrepared = true
                    chosenRotation = rot
                    width = if (rot == 90 || rot == 270) candH else candW
                    height = if (rot == 90 || rot == 270) candW else candH
                    Log.i("MainActivity", "prepareVideo accepted with rotation $rot: ${candW}x${candH}")
                    break
                }
            } catch (t: Throwable) {
                Log.w("MainActivity", "prepareVideo(view,rot=$rot) threw: ${t.message}")
            }

            // if direct view size not accepted, try scaled reductions for this rotation
            var candidateW = candW
            var candidateH = candH
            val longest = max(candidateW, candidateH)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest.toFloat()
                candidateW = even((candidateW * scale).toInt())
                candidateH = even((candidateH * scale).toInt())
            }

            var attempts = 0
            while (!videoPrepared && attempts < 8) {
                try {
                    Log.d("MainActivity", "Trying prepareVideo scaled rot=$rot ${candidateW}x${candidateH}")
                    if (rtmpCamera2.prepareVideo(candidateW, candidateH, fps, videoBitrate, rot, CameraHelper.Facing.BACK.ordinal)) {
                        videoPrepared = true
                        chosenRotation = rot
                        width = if (rot == 90 || rot == 270) candidateH else candidateW
                        height = if (rot == 90 || rot == 270) candidateW else candidateH
                        Log.i("MainActivity", "prepareVideo accepted scaled rot=$rot: ${candidateW}x${candidateH}")
                        break
                    }
                } catch (t: Throwable) {
                    Log.w("MainActivity", "prepareVideo(scaled,rot=$rot) threw: ${t.message}")
                }
                candidateW = even((candidateW * 0.75f).toInt())
                candidateH = even((candidateH * 0.75f).toInt())
                if (candidateW < 320 || candidateH < 240) break
                attempts++
            }

            if (videoPrepared) break
        }

        if (!videoPrepared) {
            Log.w("MainActivity", "Could not prepareVideo with any rotation/candidate for view ${viewW}x${viewH}")
        } else {
            Log.d("MainActivity", "Chosen rotation=$chosenRotation final encoder target=${width}x${height}")
        }

        val audioPrepared = rtmpCamera2.prepareAudio(
            audioBitrate,
            sampleRate,
            isStereo
        )

        if (!videoPrepared || !audioPrepared) {
            Log.w("MainActivity", "prepareVideo/Audio failed: videoPrepared=$videoPrepared audioPrepared=$audioPrepared")
            Toast.makeText(this, "Error preparing stream", Toast.LENGTH_LONG).show()
            return
        }

        // Start the preview only after the view is posted so the surface is ready.
        openGlView.post {
            try {
                // Diagnostic: log/Toast the encoder and view sizes to detect mismatches
                val viewW = openGlView.width
                val viewH = openGlView.height
                val encW = width
                val encH = height
                val rotStr = "${chosenRotation ?: "?"}"
                Log.d("MainActivity", "Starting preview. encoder=${encW}x${encH} view=${viewW}x${viewH} chosenRotation=${rotStr}")
                try {
                    Toast.makeText(this, "Preview: encoder=${encW}x${encH} view=${viewW}x${viewH} rot=${rotStr}", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}

                rtmpCamera2.startPreview()
                Log.d("MainActivity", "Preview started (posted)")
            } catch (e: Exception) {
                Log.w("MainActivity", "startPreview exception (posted): ${e.message}")
                try { Toast.makeText(this, "Preview failed: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            }
        }

        startAttempts = 0
        // Try to start immediately and also with a delayed retry
        attemptStartStream()
        openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)

        // start network monitor
        startNetworkMonitor()
    }

    private fun checkNetworkAndAdaptBitrate() {
        // Measure current network quality and adapt bitrate accordingly BEFORE streaming
        try {
            val rttMs = measureRttMs(StreamingConfig.RTT_MEASURE_HOST, StreamingConfig.RTT_MEASURE_PORT, StreamingConfig.RTT_MEASURE_TIMEOUT_MS)

            Log.d("MainActivity", "Network check - RTT: ${rttMs}ms")

            // If network is very poor, reduce bitrate proactively
            when {
                rttMs >= StreamingConfig.RTT_THRESHOLD_VERY_POOR -> {
                    // Very poor connection - use minimum bitrate
                    videoBitrate = StreamingConfig.BITRATE_VERY_POOR
                    width = 480
                    height = 272
                    fps = 12
                    Log.w("MainActivity", "Very poor network detected (RTT>=${StreamingConfig.RTT_THRESHOLD_VERY_POOR}ms). Using minimum bitrate: ${videoBitrate / 1000} kbps")
                    Toast.makeText(this, "Network very poor - using ${videoBitrate / 1000} kbps", Toast.LENGTH_SHORT).show()
                }
                rttMs >= StreamingConfig.RTT_THRESHOLD_POOR -> {
                    // Poor connection
                    videoBitrate = StreamingConfig.BITRATE_POOR
                    width = 640
                    height = 360
                    fps = 15
                    Log.w("MainActivity", "Poor network detected (RTT>=${StreamingConfig.RTT_THRESHOLD_POOR}ms). Using ${videoBitrate / 1000} kbps")
                    Toast.makeText(this, "Network poor - using ${videoBitrate / 1000} kbps", Toast.LENGTH_SHORT).show()
                }
                rttMs >= StreamingConfig.RTT_THRESHOLD_FAIR -> {
                    // Fair connection
                    videoBitrate = StreamingConfig.BITRATE_FAIR
                    width = 960
                    height = 540
                    fps = 24
                    Log.w("MainActivity", "Fair network detected (RTT>=${StreamingConfig.RTT_THRESHOLD_FAIR}ms). Using ${videoBitrate / 1000} kbps")
                    Toast.makeText(this, "Network fair - using ${videoBitrate / 1000} kbps", Toast.LENGTH_SHORT).show()
                }
                rttMs >= StreamingConfig.RTT_THRESHOLD_GOOD -> {
                    // Good connection
                    videoBitrate = StreamingConfig.BITRATE_GOOD
                    Log.i("MainActivity", "Good network detected. Using ${videoBitrate / 1000} kbps")
                }
                else -> {
                    // Excellent connection - use recommended
                    videoBitrate = StreamingConfig.BITRATE_EXCELLENT
                    Log.i("MainActivity", "Excellent network detected. Using ${videoBitrate / 1000} kbps")
                }
            }
            bitrateText.text = "Bitrate: ${videoBitrate / 1000} kbps"
        } catch (t: Throwable) {
            Log.w("MainActivity", "Network check failed: ${t.message}, proceeding with default bitrate")
        }
    }

    private fun stopStream() {
        // stop monitor
        stopNetworkMonitor()
        // stop timer
        stopStreamingTimer()

        if (rtmpCamera2.isStreaming) {
            rtmpCamera2.stopStream()
        }
        fallbackAttempted = false
        // stop any reconnection loop when user explicitly stops
        reconnectionJob?.cancel()
        reconnectionJob = null
        reconnectionAttempts = 0
        try {
            rtmpCamera2.stopPreview()
        } catch (_: Exception) {}
        Toast.makeText(this, "Transmission stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun attemptStartStream() {
        openGlView.post {
            try {
                if (!rtmpCamera2.isStreaming) {
                    Log.d("MainActivity", "Calling rtmpCamera2.startStream with URL=${rtmpUrl} | Bitrate=${videoBitrate / 1000}kbps | Resolution=${width}x${height}@${fps}fps")
                    rtmpCamera2.startStream(rtmpUrl)

                    val nowStreaming = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                    Log.d("MainActivity", "startStream() returned, isStreaming=$nowStreaming")
                    Toast.makeText(this, "Starting transmission... isStreaming=$nowStreaming", Toast.LENGTH_SHORT).show()
                    if (nowStreaming) {
                        streamingSince = System.currentTimeMillis()
                        startStreamingTimer()
                        fallbackAttempted = false

                    } else {
                        // if startStream didn't throw but didn't set streaming, retry a few times
                        startAttempts++
                        Log.w("MainActivity", "startStream did not set isStreaming=true, attempt=$startAttempts")
                        if (startAttempts <= maxStartAttempts) {
                            openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                        } else {
                            Log.w("MainActivity", "Exceeded start attempts, will try reconnection in background")
                            startReconnectionRetries()
                        }
                    }

                    // schedule a check after 3s to verify streaming state and offer diagnostics
                    uiHandler.postDelayed({
                        val s = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                        Log.d("MainActivity", "Delayed check: isStreaming=$s fallbackAttempted=$fallbackAttempted")
                        if (!s) {
                            Toast.makeText(this, "WARNING: the stream did not start (isStreaming=false). Attempting fallback if applicable...", Toast.LENGTH_LONG).show()
                            // Note: fallback logic for rtmp is already same as original, no rtmps conversion needed
                            if (!fallbackAttempted) {
                                val alt = rtmpUrl.replaceFirst("rtmps://", "rtmp://")
                                if (alt != rtmpUrl) {
                                    Log.i("MainActivity", "Attempting fallback to non-SSL RTMP: $alt")
                                    try {
                                        fallbackAttempted = true
                                        rtmpCamera2.startStream(alt)
                                    } catch (ex: Throwable) {
                                        Log.e("MainActivity", "Fallback startStream failed: ${ex.message}")
                                    }
                                    // schedule another check
                                    uiHandler.postDelayed({
                                        val s2 = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                                        Log.d("MainActivity", "Fallback delayed check: isStreaming=$s2")
                                        if (!s2) {
                                            Toast.makeText(this, "Fallback failed - the stream did not start. Check logs/ConnectChecker.", Toast.LENGTH_LONG).show()
                                        } else {
                                            streamingSince = System.currentTimeMillis()
                                            startStreamingTimer()
                                        }
                                    }, 3000)
                                }
                            }
                        }
                    }, 3000)

                } else {
                    Log.d("MainActivity", "Already streaming (isStreaming=true)")
                    streamingSince = streamingSince.takeIf { it != 0L } ?: System.currentTimeMillis()
                }
            } catch (e: IllegalStateException) {
                startAttempts++
                Log.w("MainActivity", "IllegalStateException starting stream attempt=$startAttempts: ${e.message}")
                if (startAttempts <= maxStartAttempts) {
                    openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                } else {
                    Log.w("MainActivity", "Exceeded start attempts due to IllegalStateException, starting background reconnection")
                    startReconnectionRetries()
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "Throwable starting stream: ${t.message}")
                Toast.makeText(this, "Stream error: ${t.message}", Toast.LENGTH_LONG).show()

                // attempt retries on other throwables as well
                startAttempts++
                if (startAttempts <= maxStartAttempts) {
                    openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                } else {
                    startReconnectionRetries()
                }
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
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        // Add READ_MEDIA_VIDEO for Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            100
        )
    }

    private fun getDeviceRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

    // Network monitor & adaptation

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
        } catch (_: Throwable) {
            return timeoutMs.toLong()
        }
    }

    private fun updateNetworkUiAndAdapt(throughputBps: Long, rttMs: Long, @Suppress("UNUSED_PARAMETER") packetLoss: Double) {
        val readable = when {
            throughputBps >= thresholdExcellent -> "Excellent"
            throughputBps >= thresholdGood -> "Good"
            throughputBps >= thresholdPoor -> "Fair"
            else -> "Poor"
        }
        networkStatusText.text = "Network: $readable"
        when (readable) {
            "Excellent" -> networkStatusText.setBackgroundColor(Color.parseColor("#8800AA00"))
            "Good" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FFD700"))
            "Fair" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FF8C00"))
            "Poor" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FF0000"))
            else -> networkStatusText.setBackgroundColor(Color.parseColor("#66000000"))
        }
        bitrateText.text = "Bitrate: ${videoBitrate / 1000} kbps"

        // Update lag indicator with color based on latency
        lagIndicatorText.text = "Lag: ${rttMs}ms"
        val lagColor = when {
            rttMs < StreamingConfig.RTT_THRESHOLD_EXCELLENT -> "#8800AA00"
            rttMs < StreamingConfig.RTT_THRESHOLD_GOOD -> "#88FFD700"
            rttMs < StreamingConfig.RTT_THRESHOLD_FAIR -> "#88FF8C00"
            else -> "#88FF0000"
        }
        lagIndicatorText.setBackgroundColor(Color.parseColor(lagColor))

        val now = System.currentTimeMillis()
        if (streamingSince == 0L || now - streamingSince < 8000L) {
            Log.d("Adaptation", "Skipping adaptation because stream not active long enough: streamingSince=$streamingSince")
            return
        }

        if (now - lastAdaptationTime < adaptationCooldownMs) return

        when (readable) {
            "Excellent" -> attemptAdaptation(3500 * 1000, 1280, 720, 30)
            "Good" -> attemptAdaptation(2000 * 1000, 1280, 720, 30)
            "Fair" -> attemptAdaptation(1000 * 1000, 960, 540, 24)
            "Poor" -> attemptAdaptation(500 * 1000, 640, 360, 15)
        }
    }

    private fun attemptAdaptation(targetBitrate: Int, targetW: Int, targetH: Int, targetFps: Int) {
        if (abs(videoBitrate - targetBitrate) < 200 * 1000 && width == targetW && height == targetH && fps == targetFps) return

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
            val method = try { cls.getMethod("setVideoBitrateOnFly", Int::class.javaPrimitiveType) } catch (_: NoSuchMethodException) { null }
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
                startStreamingTimer()
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
            Log.i("MainActivity", "onConnectionSuccess() called")
            streamingSince = System.currentTimeMillis()
            startStreamingTimer()
            networkStatusText.text = getString(R.string.network_connected)
            networkStatusText.setBackgroundColor(Color.parseColor("#8800AA00"))
            Toast.makeText(this, "Connection successful", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Log.e("MainActivity", "onConnectionFailed: $reason")
            Toast.makeText(this, "Connection failed: $reason — trying to keep transmission (degraded mode)", Toast.LENGTH_LONG).show()
            networkStatusText.text = "Connection: failed"
            networkStatusText.setBackgroundColor(Color.parseColor("#88FF0000"))
            streamingSince = 0L
            stopStreamingTimer()

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
        stopStreamingTimer()
        unregisterNetworkCallback()
        try {
            if (rtmpCamera2.isStreaming) {
                rtmpCamera2.stopStream()
            }
        } catch (_: UninitializedPropertyAccessException) {
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

    // STREAMING TIMER

    private fun startStreamingTimer() {
        stopStreamingTimer()
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                try {
                    val streamingTimeMs = System.currentTimeMillis() - streamingSince
                    val seconds = (streamingTimeMs / 1000) % 60
                    val minutes = (streamingTimeMs / (1000 * 60)) % 60
                    val hours = streamingTimeMs / (1000 * 60 * 60)

                    val timeString = String.format(
                        Locale.getDefault(),
                        "Streaming: %02d:%02d:%02d",
                        hours, minutes, seconds
                    )

                    uiHandler.post {
                        try {
                            streamingTimerText.text = timeString
                        } catch (e: Exception) {
                            Log.w("StreamingTimer", "Error updating timer text: ${e.message}")
                        }
                    }

                    delay(1000)
                } catch (e: Exception) {
                    Log.w("StreamingTimer", "Error in timer loop: ${e.message}")
                    break
                }
            }
        }
    }

    private fun stopStreamingTimer() {
        timerJob?.cancel()
        timerJob = null
        uiHandler.post {
            try {
                streamingTimerText.text = "Streaming: 00:00:00"
            } catch (e: Exception) {
                Log.w("StreamingTimer", "Error resetting timer text: ${e.message}")
            }
        }
    }
}

