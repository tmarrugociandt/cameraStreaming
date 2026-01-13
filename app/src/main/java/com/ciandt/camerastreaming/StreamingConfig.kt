package com.ciandt.camerastreaming

/**
 * Central configuration for streaming parameters and network thresholds.
 * This object contains all configurable constants for:
 * - Network quality detection (RTT thresholds)
 * - Bitrate profiles based on network quality
 * - Resolution profiles for different network conditions
 * - Timing parameters for stream adaptation
 */
object StreamingConfig {

    // ========== RTT MEASUREMENT CONFIGURATION ==========
    /** Host used to measure network latency (Round Trip Time) */
    const val RTT_MEASURE_HOST = "8.8.8.8"

    /** Port used for RTT measurement (DNS port) */
    const val RTT_MEASURE_PORT = 53

    /** Timeout in milliseconds for RTT measurement socket connection */
    const val RTT_MEASURE_TIMEOUT_MS = 1000


    // ========== LATENCY THRESHOLDS (milliseconds) ==========
    /** Threshold for excellent network: latency < 50ms */
    const val RTT_THRESHOLD_EXCELLENT = 50

    /** Threshold for good network: 50ms <= latency < 100ms */
    const val RTT_THRESHOLD_GOOD = 100

    /** Threshold for fair network: 100ms <= latency < 200ms */
    const val RTT_THRESHOLD_FAIR = 200

    /** Threshold for very poor network: latency >= 300ms */
    const val RTT_THRESHOLD_VERY_POOR = 300


    // ========== BITRATE PROFILES (bits per second) ==========
    /** Bitrate for excellent network condition: 2500 kbps */
    const val BITRATE_EXCELLENT = 2500 * 1000

    /** Bitrate for good network condition: 2000 kbps */
    const val BITRATE_GOOD = 2000 * 1000

    /** Bitrate for fair network condition: 1000 kbps */
    const val BITRATE_FAIR = 1000 * 1000

    /** Bitrate for poor network condition: 500 kbps */
    const val BITRATE_POOR = 500 * 1000

    /** Bitrate for very poor network condition: 300 kbps (minimum) */
    const val BITRATE_VERY_POOR = 300 * 1000


    // ========== RESOLUTION PROFILE: EXCELLENT ==========
    /** Video width for excellent network: 1280px (1280x720 @30fps) */
    const val WIDTH_EXCELLENT = 1280

    /** Video height for excellent network: 720px (1280x720 @30fps) */
    const val HEIGHT_EXCELLENT = 720

    /** Frames per second for excellent network: 30 fps */
    const val FPS_EXCELLENT = 30


    // ========== RESOLUTION PROFILE: GOOD ==========
    /** Video width for good network: 1280px (1280x720 @30fps) */
    const val WIDTH_GOOD = 1280

    /** Video height for good network: 720px (1280x720 @30fps) */
    const val HEIGHT_GOOD = 720

    /** Frames per second for good network: 30 fps */
    const val FPS_GOOD = 30


    // ========== RESOLUTION PROFILE: FAIR ==========
    /** Video width for fair network: 960px (960x540 @24fps) */
    const val WIDTH_FAIR = 960

    /** Video height for fair network: 540px (960x540 @24fps) */
    const val HEIGHT_FAIR = 540

    /** Frames per second for fair network: 24 fps */
    const val FPS_FAIR = 24


    // ========== RESOLUTION PROFILE: POOR ==========
    /** Video width for poor network: 640px (640x360 @15fps) */
    const val WIDTH_POOR = 640

    /** Video height for poor network: 360px (640x360 @15fps) */
    const val HEIGHT_POOR = 360

    /** Frames per second for poor network: 15 fps */
    const val FPS_POOR = 15


    // ========== RESOLUTION PROFILE: VERY POOR ==========
    /** Video width for very poor network: 480px (480x272 @12fps) */
    const val WIDTH_VERY_POOR = 480

    /** Video height for very poor network: 272px (480x272 @12fps) */
    const val HEIGHT_VERY_POOR = 272

    /** Frames per second for very poor network: 12 fps */
    const val FPS_VERY_POOR = 12


    // ========== STREAMING CONFIGURATION TIMERS (milliseconds) ==========
    /**
     * Time to wait before allowing the first adaptation.
     * Allows stream to stabilize before making quality adjustments.
     */
    const val STABILIZATION_TIME_MS = 15000L  // 15 seconds

    /**
     * Cooldown period between consecutive adaptation attempts.
     * Prevents rapid quality changes and oscillation.
     */
    const val ADAPTATION_COOLDOWN_MS = 5000L  // 5 seconds
}

