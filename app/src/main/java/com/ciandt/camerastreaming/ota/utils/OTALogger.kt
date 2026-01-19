package com.ciandt.camerastreaming.ota.utils

import android.util.Log

/**
 * Logger for the OTA module
 */
object OTALogger {
    private const val TAG = "OTA"

    fun d(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.d(TAG, message, throwable)
        } else {
            Log.d(TAG, message)
        }
    }

    fun i(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.i(TAG, message, throwable)
        } else {
            Log.i(TAG, message)
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(TAG, message, throwable)
        } else {
            Log.w(TAG, message)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}

