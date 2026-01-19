package com.ciandt.camerastreaming.ota.models

import java.io.Serializable

/**
 * Information about an available update
 * Supports both remote URLs and local file paths
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val description: String = "",
    val releaseDate: String = "",
    val apkUrl: String,
    val fileSize: Long,
    val releaseNotes: String = "",
    val isMandatory: Boolean = false,
    val minSdkVersion: Int = 0,
    val buildId: String = "",
    val deviceRequirements: Map<String, String> = emptyMap(),
    val isLocalSource: Boolean = false // Indicates if apkUrl is a local file path
) : Serializable

