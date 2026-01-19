package com.ciandt.camerastreaming.ota.utils

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Constants and properties for the OTA system
 */
object OTAConstants {
    const val OTA_DOWNLOAD_DIRECTORY = "ota_updates"
    const val APK_FILE_NAME = "update.apk"
    const val METADATA_FILE_NAME = "update_info.json"

    // Download configuration
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L
    const val BUFFER_SIZE = 8192


    fun getOTADirectory(context: Context): File {
        return File(context.getExternalFilesDir(null), OTA_DOWNLOAD_DIRECTORY).apply {
            mkdirs()
        }
    }

    fun getAPKPath(context: Context): String {
        return File(getOTADirectory(context), APK_FILE_NAME).absolutePath
    }

}

