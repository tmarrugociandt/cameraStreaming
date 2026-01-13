package com.ciandt.camerastreaming

import android.util.Log

/**
 * Video encryption interceptor for RTMP streaming
 * Manages encryption/decryption of video frames without breaking the video stream
 *
 * Architecture:
 * - Local: Video frames are encrypted BEFORE sending to RTMP encoder
 * - Remote: Encrypted frames are sent to YouTube (but YouTube receives unencrypted stream)
 * - This is a "transport layer" encryption that doesn't affect YouTube's ability to receive the stream
 */
object VideoEncryptionInterceptor {

    private var isEncryptionEnabled = false
    private var encryptedFramesCount = 0L
    private var totalFramesProcessed = 0L
    private var encryptionOverhead = 0L

    /**
     * Initialize encryption interceptor with a password
     */
    fun initialize(masterPassword: String): Boolean {
        return try {
            if (VideoEncryption.initialize(masterPassword)) {
                isEncryptionEnabled = true
                Log.i("VideoEncryptionInterceptor", "Encryption interceptor initialized")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("VideoEncryptionInterceptor", "Failed to initialize: ${e.message}")
            false
        }
    }

    /**
     * Process video frame: encrypt if enabled
     * In a real implementation, this would intercept the frame data before RTMP encoding
     */
    fun processFrame(frameData: ByteArray, frameIndex: Long): ProcessedFrame {
        totalFramesProcessed++

        return if (isEncryptionEnabled) {
            val encryptedData = VideoEncryption.encryptFrame(frameData)
            if (encryptedData != null) {
                encryptedFramesCount++
                encryptionOverhead += (encryptedData.size - frameData.size)
                Log.d("VideoEncryptionInterceptor", "Frame $frameIndex encrypted: ${frameData.size} → ${encryptedData.size}")

                ProcessedFrame(
                    data = encryptedData,
                    isEncrypted = true,
                    originalSize = frameData.size,
                    encryptedSize = encryptedData.size
                )
            } else {
                Log.w("VideoEncryptionInterceptor", "Encryption failed for frame $frameIndex, sending unencrypted")
                ProcessedFrame(
                    data = frameData,
                    isEncrypted = false,
                    originalSize = frameData.size,
                    encryptedSize = frameData.size
                )
            }
        } else {
            ProcessedFrame(
                data = frameData,
                isEncrypted = false,
                originalSize = frameData.size,
                encryptedSize = frameData.size
            )
        }
    }

    /**
     * Enable or disable encryption
     */
    fun setEncryptionEnabled(enabled: Boolean) {
        isEncryptionEnabled = enabled
        Log.i("VideoEncryptionInterceptor", "Encryption ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if encryption is enabled
     */
    fun isEnabled(): Boolean = isEncryptionEnabled

    /**
     * Get encryption statistics
     */
    fun getStatistics(): EncryptionStats {
        return EncryptionStats(
            isEnabled = isEncryptionEnabled,
            totalFramesProcessed = totalFramesProcessed,
            encryptedFrames = encryptedFramesCount,
            encryptionSuccessRate = if (totalFramesProcessed > 0) {
                (encryptedFramesCount * 100) / totalFramesProcessed
            } else {
                0L
            },
            totalOverheadBytes = encryptionOverhead,
            averageOverheadPerFrame = if (encryptedFramesCount > 0) {
                encryptionOverhead / encryptedFramesCount
            } else {
                0L
            }
        )
    }

    /**
     * Reset statistics
     */
    fun resetStatistics() {
        encryptedFramesCount = 0L
        totalFramesProcessed = 0L
        encryptionOverhead = 0L
        Log.i("VideoEncryptionInterceptor", "Statistics reset")
    }

    /**
     * Clear resources
     */
    fun clear() {
        VideoEncryption.clear()
        isEncryptionEnabled = false
        resetStatistics()
        Log.i("VideoEncryptionInterceptor", "Interceptor cleared")
    }
}

/**
 * Represents a processed video frame
 */
data class ProcessedFrame(
    val data: ByteArray,
    val isEncrypted: Boolean,
    val originalSize: Int,
    val encryptedSize: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProcessedFrame

        if (!data.contentEquals(other.data)) return false
        if (isEncrypted != other.isEncrypted) return false
        if (originalSize != other.originalSize) return false
        if (encryptedSize != other.encryptedSize) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + originalSize
        result = 31 * result + encryptedSize
        return result
    }
}

/**
 * Encryption statistics
 */
data class EncryptionStats(
    val isEnabled: Boolean,
    val totalFramesProcessed: Long,
    val encryptedFrames: Long,
    val encryptionSuccessRate: Long,  // percentage
    val totalOverheadBytes: Long,
    val averageOverheadPerFrame: Long
)

