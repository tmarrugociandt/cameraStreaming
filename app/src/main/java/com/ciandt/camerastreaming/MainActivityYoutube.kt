package com.ciandt.camerastreaming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
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

class MainActivityYoutube : AppCompatActivity(), ConnectChecker {

    private lateinit var rtmpCamera2: RtmpCamera2
    private lateinit var openGlView: OpenGlView

    private lateinit var networkStatusText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var streamingTimerText: TextView

    private var streamKey: String = "bmd8-msjr-zrvg-jzz9-6m10"

    // rtmp URL is computed from current streamKey
    private fun getRtmpUrl(): String = "rtmps://a.rtmps.youtube.com/live2/$streamKey"

    // 🎥 Recommended YouTube configuration: 720p
    private var width = 1280
    private var height = 720
    private var fps = 30
    private var videoBitrate = 2500 * 1000 // 2.5 Mbps
    private val audioBitrate = 128 * 1000
    private val sampleRate = 44100
    private val isStereo = true

    // 🔁 RETRY POLICY
    private val maxStartAttempts = 20
    private var startAttempts = 0
    private val startRetryDelayMs = 1000L

    // reconnection control (don't stop streaming permanently on transient failures)
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
    // Timer job para actualizar el UI cada segundo
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

        rtmpCamera2 = RtmpCamera2(openGlView, this)
        // register network callback to handle wifi <-> mobile transitions
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()

        startButton.setOnClickListener {
            if (!rtmpCamera2.isStreaming) {
                if (checkPermissions()) {
                    // Before starting the stream, ensure a stream key is present
                    startStream()
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

        // initialize bitrate indicator
        bitrateText.text = getString(R.string.bitrate_label)
    }





    // Demo: append encrypted bytes to a local file (so you can inspect transitively)



    private fun startStream() {
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
                Log.d("MainActivityYoutube", "Trying prepareVideo with rotation $rot and view size ${candW}x${candH}")
                if (rtmpCamera2.prepareVideo(candW, candH, fps, videoBitrate, rot, CameraHelper.Facing.BACK.ordinal)) {
                    videoPrepared = true
                    chosenRotation = rot
                    width = if (rot == 90 || rot == 270) candH else candW
                    height = if (rot == 90 || rot == 270) candW else candH
                    Log.i("MainActivityYoutube", "prepareVideo accepted with rotation $rot: ${candW}x${candH}")
                    break
                }
            } catch (t: Throwable) {
                Log.w("MainActivityYoutube", "prepareVideo(view,rot=$rot) threw: ${t.message}")
            }

            // if direct view size not accepted, try scaled reductions for this rotation
            var candidateW = candW
            var candidateH = candH
            val longest = kotlin.math.max(candidateW, candidateH)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest.toFloat()
                candidateW = even((candidateW * scale).toInt())
                candidateH = even((candidateH * scale).toInt())
            }

            var attempts = 0
            while (!videoPrepared && attempts < 8) {
                try {
                    Log.d("MainActivityYoutube", "Trying prepareVideo scaled rot=$rot ${candidateW}x${candidateH}")
                    if (rtmpCamera2.prepareVideo(candidateW, candidateH, fps, videoBitrate, rot, CameraHelper.Facing.BACK.ordinal)) {
                        videoPrepared = true
                        chosenRotation = rot
                        width = if (rot == 90 || rot == 270) candidateH else candidateW
                        height = if (rot == 90 || rot == 270) candidateW else candidateH
                        Log.i("MainActivityYoutube", "prepareVideo accepted scaled rot=$rot: ${candidateW}x${candidateH}")
                        break
                    }
                } catch (t: Throwable) {
                    Log.w("MainActivityYoutube", "prepareVideo(scaled,rot=$rot) threw: ${t.message}")
                }
                candidateW = even((candidateW * 0.75f).toInt())
                candidateH = even((candidateH * 0.75f).toInt())
                if (candidateW < 320 || candidateH < 240) break
                attempts++
            }

            if (videoPrepared) break
        }

        if (!videoPrepared) {
            Log.w("MainActivityYoutube", "Could not prepareVideo with any rotation/candidate for view ${viewW}x${viewH}")
        } else {
            Log.d("MainActivityYoutube", "Chosen rotation=$chosenRotation final encoder target=${width}x${height}")
        }

        val audioPrepared = rtmpCamera2.prepareAudio(
            audioBitrate,
            sampleRate,
            isStereo
        )

        if (!videoPrepared || !audioPrepared) {
            Log.w("MainActivityYoutube", "prepareVideo/Audio failed: videoPrepared=$videoPrepared audioPrepared=$audioPrepared")
            Toast.makeText(this, "Error preparing YouTube stream", Toast.LENGTH_LONG).show()
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
                Log.d("MainActivityYoutube", "Starting preview. encoder=${encW}x${encH} view=${viewW}x${viewH} chosenRotation=${rotStr}")
                try {
                    Toast.makeText(this, "Preview: encoder=${encW}x${encH} view=${viewW}x${viewH} rot=${rotStr}", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}

                rtmpCamera2.startPreview()
                Log.d("MainActivityYoutube", "Preview started (posted)")
            } catch (e: Exception) {
                Log.w("MainActivityYoutube", "startPreview exception (posted): ${e.message}")
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

    private fun attemptStartStream() {
        openGlView.post {
            try {
                if (!rtmpCamera2.isStreaming) {
                    Log.d("MainActivityYoutube", "Calling rtmpCamera2.startStream with URL=${getRtmpUrl()}")
                    rtmpCamera2.startStream(getRtmpUrl())
                    // registrar si quedó en streaming
                    val nowStreaming = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                    Log.d("MainActivityYoutube", "startStream() returned, isStreaming=$nowStreaming")
                    Toast.makeText(this, "Starting YouTube Live... isStreaming=$nowStreaming", Toast.LENGTH_SHORT).show()
                    if (nowStreaming) {
                        streamingSince = System.currentTimeMillis()
                        startStreamingTimer()
                        fallbackAttempted = false

                    } else {
                        // if startStream didn't throw but didn't set streaming, retry a few times
                        startAttempts++
                        Log.w("MainActivityYoutube", "startStream did not set isStreaming=true, attempt=$startAttempts")
                        if (startAttempts <= maxStartAttempts) {
                            openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                        } else {
                            Log.w("MainActivityYoutube", "Exceeded start attempts, will try reconnection in background")
                            startReconnectionRetries()
                        }
                    }

                    // schedule a check after 3s to verify streaming state and offer diagnostics
                    uiHandler.postDelayed({
                        val s = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                        Log.d("MainActivityYoutube", "Delayed check: isStreaming=$s fallbackAttempted=$fallbackAttempted")
                        if (!s) {
                            Toast.makeText(this, "WARNING: the stream did not start (isStreaming=false). Attempting fallback if applicable...", Toast.LENGTH_LONG).show()
                            // try fallback to rtmp:// if we haven't attempted it yet
                            if (!fallbackAttempted) {
                                val alt = getRtmpUrl().replaceFirst("rtmps://", "rtmp://")
                                if (alt != getRtmpUrl()) {
                                    Log.i("MainActivityYoutube", "Attempting fallback to non-SSL RTMP: $alt")
                                    try {
                                        fallbackAttempted = true
                                        rtmpCamera2.startStream(alt)
                                    } catch (ex: Throwable) {
                                        Log.e("MainActivityYoutube", "Fallback startStream failed: ${ex.message}")
                                    }
                                    // schedule another check
                                    uiHandler.postDelayed({
                                        val s2 = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
                                        Log.d("MainActivityYoutube", "Fallback delayed check: isStreaming=$s2")
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
                    Log.d("MainActivityYoutube", "Already streaming (isStreaming=true)")
                    streamingSince = streamingSince.takeIf { it != 0L } ?: System.currentTimeMillis()
                }
            } catch (e: IllegalStateException) {
                startAttempts++
                Log.w("MainActivityYoutube", "IllegalStateException starting stream attempt=$startAttempts: ${e.message}")
                if (startAttempts <= maxStartAttempts) {
                    openGlView.postDelayed({ attemptStartStream() }, startRetryDelayMs)
                } else {
                    Log.w("MainActivityYoutube", "Exceeded start attempts due to IllegalStateException, starting background reconnection")
                    startReconnectionRetries()
                }
            } catch (t: Throwable) {
                Log.e("MainActivityYoutube", "Throwable starting stream: ${t.message}")
                Toast.makeText(this, "YouTube stream error: ${t.message}", Toast.LENGTH_LONG).show()
                
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
        Toast.makeText(this, "YouTube stream stopped", Toast.LENGTH_SHORT).show()
    }

    private fun startReconnectionRetries() {
        // if already trying, skip
        if (reconnectionJob?.isActive == true) return
        reconnectionJob = CoroutineScope(Dispatchers.Main).launch {
            reconnectionAttempts = 0
            while (isActive && reconnectionAttempts < maxReconnectionAttempts && !tryIsStreaming()) {
                val delayMs = reconnectionBaseDelayMs * (reconnectionAttempts + 1)
                Log.i("MainActivityYoutube", "Reconnection attempt ${reconnectionAttempts + 1}, waiting ${delayMs}ms")
                delay(delayMs)
                try {
                    // try start stream without restoring preview/encoders (preview should remain active)
                    attemptStartStream()
                } catch (t: Throwable) {
                    Log.w("MainActivityYoutube", "Reconnection attempt failed: ${t.message}")
                }
                reconnectionAttempts++
            }
            if (tryIsStreaming()) {
                Log.i("MainActivityYoutube", "Reconnected after ${reconnectionAttempts} attempts")
                Toast.makeText(this@MainActivityYoutube, "Reconnected", Toast.LENGTH_SHORT).show()
                streamingSince = System.currentTimeMillis()
                startStreamingTimer()
            } else {
                Log.w("MainActivityYoutube", "Could not reconnect after $reconnectionAttempts attempts")
                uiHandler.post {
                    Toast.makeText(this@MainActivityYoutube, "Could not reconnect. The app will keep trying in background.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun tryIsStreaming(): Boolean {
        return try { rtmpCamera2.isStreaming } catch (_: Exception) { false }
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

    // NETWORK MONITOR + ADAPTATION

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

                // lightweight RTT check: connect to 8.8.8.8:53 (DNS) with short timeout
                val rttMs = measureRttMs("8.8.8.8", 53, 1000)

                // simplistic packet-loss proxy: if socket connect fails repeatedly count as poor
                val loss = if (throughput == 0 && rttMs >= 1000) 1.0 else 0.0

                // log isStreaming state for debugging
                val streamingState = try { if (this@MainActivityYoutube::rtmpCamera2.isInitialized) rtmpCamera2.isStreaming else false } catch (_: Exception) { false }

                Log.d("NetMonitor", "throughput=$throughput B/s rtt=${rttMs}ms loss=$loss isStreaming=$streamingState streamingSince=$streamingSince")

                // Post results to UI and adapt
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
        // produce English quality labels directly
        val readable = when {
            throughputBps >= thresholdExcellent -> "Excellent"
            throughputBps >= thresholdGood -> "Good"
            throughputBps >= thresholdPoor -> "Fair"
            else -> "Poor"
        }
        networkStatusText.text = "Network: $readable (rtt=${rttMs}ms)"
        // change background color according to quality
        when (readable) {
            "Excellent" -> networkStatusText.setBackgroundColor(Color.parseColor("#8800AA00")) // light green
            "Good" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FFD700")) // yellow
            "Fair" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FF8C00")) // orange
            "Poor" -> networkStatusText.setBackgroundColor(Color.parseColor("#88FF0000")) // red
            else -> networkStatusText.setBackgroundColor(Color.parseColor("#66000000"))
        }
        bitrateText.text = "Bitrate: ${videoBitrate / 1000} kbps"

        // decide adaptation
        val now = System.currentTimeMillis()
        // don't adapt if stream hasn't been active for at least 8s (give time to start)
        if (streamingSince == 0L || now - streamingSince < 8000L) {
            Log.d("Adaptation", "Skipping adaptation because stream not active long enough: streamingSince=$streamingSince")
            return
        }

        if (now - lastAdaptationTime < adaptationCooldownMs) return

        when (readable) {
            "Excellent" -> {
                // try to increase to high profile 2500-4000 kbps
                val target = 3500 * 1000
                attemptAdaptation(target, width, height, 30)
            }
            "Good" -> {
                val target = 2000 * 1000
                attemptAdaptation(target, width, height, 30)
            }
            "Fair" -> {
                // lower bitrate and possibly lower resolution
                val target = 1000 * 1000
                attemptAdaptation(target, 960, 540, 24)
            }
            "Poor" -> {
                val target = 500 * 1000
                attemptAdaptation(target, 640, 360, 15)
            }
        }
    }

    private fun attemptAdaptation(targetBitrate: Int, targetW: Int, targetH: Int, targetFps: Int) {
        // Avoid flapping: only adapt if significant change
        if (kotlin.math.abs(videoBitrate - targetBitrate) < 200 * 1000 && width == targetW && height == targetH && fps == targetFps) return

        lastAdaptationTime = System.currentTimeMillis()
        Log.i("Adaptation", "Adapting to bitrate=$targetBitrate, ${targetW}x${targetH}@${targetFps}")

        // Save old settings so we can revert if reconfiguration fails
        val oldBitrate = videoBitrate
        val oldWidth = width
        val oldHeight = height
        val oldFps = fps

        // try to update bitrate on-the-fly using reflection if available
        val successSetOnFly = trySetBitrateOnFly(targetBitrate)

        if (successSetOnFly) {
            videoBitrate = targetBitrate
            bitrateText.text = "Bitrate: ${videoBitrate / 1000} kbps"
            showAdaptationToast("Adaptation: bitrate ${videoBitrate / 1000} kbps")
            return
        }

        // If on-the-fly not available, attempt to reconfigure video encoder while streaming
        CoroutineScope(Dispatchers.Main).launch {
            val wasStreaming = try { rtmpCamera2.isStreaming } catch (_: Exception) { false }

            // stop preview to reprepare video (library may require stopping preview)
            try {
                rtmpCamera2.stopPreview()
            } catch (_: Exception) {}

            // update local vars to requested
            width = targetW
            height = targetH
            fps = targetFps

            val encoderRotation = getEncoderRotation()
            val (videoW, videoH) =
                if (encoderRotation == 90 || encoderRotation == 270)
                    Pair(height, width)
                else
                    Pair(width, height)

            val prepared = try {
                rtmpCamera2.prepareVideo(videoW, videoH, fps, targetBitrate, encoderRotation, CameraHelper.Facing.BACK.ordinal)
            } catch (t: Throwable) {
                Log.w("Adaptation", "prepareVideo threw: ${t.message}")
                false
            }

            if (!prepared) {
                // Revert to previous settings to avoid dropping the stream
                Log.w("Adaptation", "Could not prepare video with new configuration, reverting to previous settings")
                showAdaptationToast("Adaptation failed: reverting to previous settings")

                // restore old vars
                width = oldWidth
                height = oldHeight
                fps = oldFps
                // try to reprepare previous encoder settings
                try {
                    val prevEncoderRotation = getEncoderRotation()
                    val (prevW, prevH) = if (prevEncoderRotation == 90 || prevEncoderRotation == 270) Pair(height, width) else Pair(width, height)
                    rtmpCamera2.prepareVideo(prevW, prevH, fps, oldBitrate, prevEncoderRotation, CameraHelper.Facing.BACK.ordinal)
                } catch (t: Throwable) {
                    Log.w("Adaptation", "Re-prepare previous settings threw: ${t.message}")
                }

                // restart preview
                try {
                    rtmpCamera2.startPreview()
                } catch (_: Exception) {}

                // if was streaming before, attempt to ensure stream is active
                if (wasStreaming) {
                    try {
                        if (!tryIsStreaming()) {
                            Log.i("Adaptation", "Attempting to restart stream after failed adaptation")
                            rtmpCamera2.startStream(getRtmpUrl())
                        }
                    } catch (t: Throwable) {
                        Log.e("Adaptation", "Error restarting stream after failed adaptation: ${t.message}")
                    }
                }

                return@launch
            }

            // commit new settings
            videoBitrate = targetBitrate
            bitrateText.text = "Bitrate: ${videoBitrate / 1000} kbps"

            // restart preview
            try {
                rtmpCamera2.startPreview()
            } catch (_: Exception) {}

            // if was streaming, try to ensure stream continues (restart if necessary)
            if (wasStreaming) {
                try {
                    if (!tryIsStreaming()) rtmpCamera2.startStream(getRtmpUrl())
                } catch (t: Throwable) {
                    Log.e("Adaptation", "Error re-starting stream after adaptation: ${t.message}")
                }
            }

            showAdaptationToast("Adaptation applied: ${videoBitrate / 1000} kbps, ${width}x${height}@${fps}")
        }
    }

    private fun trySetBitrateOnFly(targetBitrate: Int): Boolean {
        return try {
            // Many streaming libs expose a method like setVideoBitrateOnFly or bitrateAdapter; try reflectively
            val cls = rtmpCamera2::class.java
            val method = try { cls.getMethod("setVideoBitrateOnFly", Int::class.javaPrimitiveType) } catch (e: NoSuchMethodException) { null }
            if (method != null) {
                method.invoke(rtmpCamera2, targetBitrate)
                Log.i("Adaptation", "setVideoBitrateOnFly invoked")
                true
            } else {
                // try BitrateAdapter listener pattern - not implemented here, return false
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


    // 🔌 CONNECT CHECKER CALLBACKS

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        runOnUiThread {
            Log.i("MainActivityYoutube", "onConnectionSuccess() called")
            // mark that the stream is active from this moment to allow adaptations afterwards
            streamingSince = System.currentTimeMillis()
            startStreamingTimer()
            networkStatusText.text = getString(R.string.network_connected)
            networkStatusText.setBackgroundColor(Color.parseColor("#8800AA00"))
            Toast.makeText(this, "Connected to YouTube", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Log.e("MainActivityYoutube", "onConnectionFailed: $reason")
            // Do not stop the stream: reduce bitrate/resolution to keep sending and try to reconnect
            Toast.makeText(this, "Connection failed: $reason — trying to keep transmission (degraded mode)", Toast.LENGTH_LONG).show()
            networkStatusText.text = "Connection: failed"
            networkStatusText.setBackgroundColor(Color.parseColor("#88FF0000"))
            // mark disconnected time
            streamingSince = 0L
            stopStreamingTimer()

            // aggressively lower quality to maintain transmission if possible
            try {
                attemptAdaptation(300 * 1000, 480, 272, 12) // 300 kbps, low res
            } catch (t: Throwable) {
                Log.w("MainActivityYoutube", "Degraded adaptation failed: ${t.message}")
            }

            // start background reconnection attempts (without stopping preview)
            startReconnectionRetries()
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

    override fun onDestroy() {
        super.onDestroy()
        stopNetworkMonitor()
        stopStreamingTimer()
        unregisterNetworkCallback()
        // don't forcibly stop stream here; if active, stop gracefully
        try {
            if (rtmpCamera2.isStreaming) {
                rtmpCamera2.stopStream()
            }
        } catch (e: UninitializedPropertyAccessException) {
            // rtmpCamera2 wasn't initialized; nothing to stop
        } catch (t: Throwable) {
            // any other error stopping the stream shouldn't crash onDestroy
            Log.w("MainActivityYoutube", "Error stopping stream in onDestroy: ${t.message}")
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
                    // unbind process network so new sockets use system default (may switch to other transports)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connectivityManager.bindProcessToNetwork(null)
                            Log.i("NetworkCallback", "Process unbound from lost network")
                        }
                    } catch (t: Throwable) {
                        Log.w("NetworkCallback", "unbind process network failed: ${t.message}")
                    }
                     // leave preview running; reconnection will trigger on available
                 }
             }

             override fun onAvailable(network: Network) {
                 super.onAvailable(network)
                 uiHandler.post {
                     Log.i("NetworkCallback", "Network available")
                     networkLost = false
                     networkStatusText.text = getString(R.string.network_connected)
                     try { networkStatusText.setBackgroundColor(Color.parseColor("#8800AA00")) } catch (_: Exception) {}
                     // bind process to this network so outgoing sockets use it (helps on network switch)
                     try {
                         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                             connectivityManager.bindProcessToNetwork(network)
                             Log.i("NetworkCallback", "Process bound to new network")
                         }
                     } catch (t: Throwable) {
                         Log.w("NetworkCallback", "bind process network failed: ${t.message}")
                     }
                     // if we were streaming before the loss or had start attempts, try reconnection
                     if (hadStreamingBeforeNetworkLoss || startAttempts > 0) {
                         Log.i("NetworkCallback", "Trigger reconnection after network available")
                        // try immediate start and schedule background retries
                        // If we were streaming before the network switch, do a clean restart:
                        // stopping the RTMP connection first ensures sockets are recreated on the new network.
                        try {
                            if (hadStreamingBeforeNetworkLoss) {
                                try {
                                    if (rtmpCamera2.isStreaming) {
                                        Log.i("NetworkCallback", "Stopping RTMP stream to restart on new network")
                                        // stopStream() cancels reconnection job; we will schedule a new start below
                                        rtmpCamera2.stopStream()
                                    }
                                } catch (t: Throwable) {
                                    Log.w("NetworkCallback", "Stopping RTMP stream failed: ${t.message}")
                                }
                            }
                            // small delay to allow network binding to settle, then attempt start
                            uiHandler.postDelayed({
                                try {
                                    attemptStartStream()
                                } catch (t: Throwable) {
                                    Log.w("NetworkCallback", "Delayed attemptStartStream failed: ${t.message}")
                                }
                            }, 700)
                        } catch (t: Throwable) {
                            Log.w("NetworkCallback", "Immediate restart flow failed: ${t.message}")
                        }
                        // always start background reconnection attempts as a fallback
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
