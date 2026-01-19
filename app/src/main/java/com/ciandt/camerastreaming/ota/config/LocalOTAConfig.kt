package com.ciandt.camerastreaming.ota.config

import android.content.Context
import java.io.File

/**
 * Configuration for Local OTA updates
 * Provides centralized configuration for the OTA system
 */
object LocalOTAConfig {

    // Enable debug logging
    var enableDebugLogging: Boolean = true

    // Default OTA directories
    private const val DEFAULT_OTA_DIRECTORY = "ota_updates"
    private const val DEFAULT_DOWNLOAD_DIRECTORY = "ota_downloads"

    // File provider authority (must match manifest)
    var fileProviderAuthority: String = ""
        private set

    // APK validation settings
    var validateSignatures: Boolean = true
    var validateFileSize: Boolean = true
    var validateFileHash: Boolean = false

    // Installation settings
    var autoInstallWhenReady: Boolean = false
    var showInstallNotification: Boolean = true
    var showDownloadProgress: Boolean = true

    // Cleanup settings
    var autoCleanupOldUpdates: Boolean = true
    var cleanupIntervalHours: Long = 24
    var maxOldFilesCount: Int = 3

    // Directory paths
    private var otaDirectory: File? = null
    private var downloadDirectory: File? = null

    /**
     * Initialize configuration with context
     */
    fun initialize(context: Context, authority: String = "${context.packageName}.fileprovider") {
        fileProviderAuthority = authority
        otaDirectory = File(context.getExternalFilesDir(null), DEFAULT_OTA_DIRECTORY).apply {
            mkdirs()
        }
        downloadDirectory = File(context.getExternalFilesDir(null), DEFAULT_DOWNLOAD_DIRECTORY).apply {
            mkdirs()
        }
    }

    /**
     * Get OTA update directory
     */
    fun getOTADirectory(): File {
        return otaDirectory ?: throw IllegalStateException("LocalOTAConfig not initialized. Call initialize() first.")
    }

    /**
     * Get download directory for APK files
     */
    fun getDownloadDirectory(): File {
        return downloadDirectory ?: throw IllegalStateException("LocalOTAConfig not initialized. Call initialize() first.")
    }

    /**
     * Set custom OTA directory
     */
    fun setOTADirectory(path: String) {
        otaDirectory = File(path).apply { mkdirs() }
    }

    /**
     * Set custom download directory
     */
    fun setDownloadDirectory(path: String) {
        downloadDirectory = File(path).apply { mkdirs() }
    }

    /**
     * Get configuration summary
     */
    fun getConfigurationSummary(): String {
        return """
            LocalOTA Configuration:
            - Debug Logging: $enableDebugLogging
            - Validate Signatures: $validateSignatures
            - Validate File Size: $validateFileSize
            - Validate File Hash: $validateFileHash
            - Auto Install: $autoInstallWhenReady
            - Show Notification: $showInstallNotification
            - Show Progress: $showDownloadProgress
            - Auto Cleanup: $autoCleanupOldUpdates
            - Cleanup Interval: ${cleanupIntervalHours}h
            - OTA Directory: ${otaDirectory?.absolutePath}
            - Download Directory: ${downloadDirectory?.absolutePath}
        """.trimIndent()
    }

    /**
     * Reset configuration to defaults
     */
    fun reset() {
        enableDebugLogging = true
        validateSignatures = true
        validateFileSize = true
        validateFileHash = false
        autoInstallWhenReady = false
        showInstallNotification = true
        showDownloadProgress = true
        autoCleanupOldUpdates = true
        cleanupIntervalHours = 24
        maxOldFilesCount = 3
    }
}

