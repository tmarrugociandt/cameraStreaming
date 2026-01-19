package com.ciandt.camerastreaming.ota.models

/**
 * Possible states during the update flow
 */
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class Available(val updateInfo: UpdateInfo) : UpdateStatus()
    data class NotAvailable(val reason: String = "") : UpdateStatus()
    object Downloading : UpdateStatus()
    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) : UpdateStatus()
    object Downloaded : UpdateStatus()
    object Verifying : UpdateStatus()
    object Ready : UpdateStatus()
    object Installing : UpdateStatus()
    data class Error(val exception: Exception, val stage: String = "") : UpdateStatus()
    data class Cancelled(val message: String = "") : UpdateStatus()
}

