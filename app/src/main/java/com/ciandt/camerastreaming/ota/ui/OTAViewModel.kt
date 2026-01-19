package com.ciandt.camerastreaming.ota.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ciandt.camerastreaming.ota.core.LocalUpdateManager
import com.ciandt.camerastreaming.ota.models.UpdateInfo
import com.ciandt.camerastreaming.ota.models.UpdateStatus
import com.ciandt.camerastreaming.ota.utils.OTALogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for managing Local OTA UI
 *
 * This ViewModel now ONLY handles LOCAL OTA updates.
 * Remote OTA (downloading from URL) has been completely removed.
 */
class OTAViewModel(application: Application) : AndroidViewModel(application) {

    private val localUpdateManager = LocalUpdateManager(application.applicationContext)

    private val _uiState = MutableStateFlow<UIState>(UIState.Idle)
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    private val _currentUpdateInfo = MutableStateFlow<UpdateInfo?>(null)
    val currentUpdateInfo: StateFlow<UpdateInfo?> = _currentUpdateInfo.asStateFlow()

    private val _installedVersion = MutableStateFlow(getInstalledVersion())
    val installedVersion: StateFlow<String> = _installedVersion.asStateFlow()

    init {
        OTALogger.i("OTAViewModel initialized - LOCAL OTA ONLY")
    }

    /**
     * Starts checking for updates from local storage
     */
    fun checkForUpdates(dirPath: String? = null) {
        viewModelScope.launch {
            try {
                // Use /storage/emulated/0/Download/ota_updates as default path
                val searchPath = dirPath ?: android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ).absolutePath + "/ota_updates"

                _uiState.value = UIState.Checking
                OTALogger.i("Checking for updates in: $searchPath")

                val status = localUpdateManager.checkForLocalUpdates(searchPath)

                when (status) {
                    is UpdateStatus.Available -> {
                        _currentUpdateInfo.value = status.updateInfo
                        _uiState.value = UIState.UpdateAvailable(status.updateInfo)
                        OTALogger.i("Update available: ${status.updateInfo.versionName}")
                    }
                    is UpdateStatus.NotAvailable -> {
                        _uiState.value = UIState.NoUpdates("No updates available in local storage")
                        OTALogger.i("No updates available")
                    }
                    is UpdateStatus.Error -> {
                        _uiState.value = UIState.Error("Error checking updates: ${status.exception.message}")
                        OTALogger.e("Error checking updates", status.exception)
                    }
                    else -> {
                        _uiState.value = UIState.Idle
                    }
                }
            } catch (e: Exception) {
                OTALogger.e("Error checking for updates", e)
                _uiState.value = UIState.Error("Error checking for updates: ${e.message}")
            }
        }
    }

    /**
     * Prepares and validates the APK
     */
    fun prepareAndValidateAPK(apkPath: String) {
        viewModelScope.launch {
            try {
                _uiState.value = UIState.Verifying
                OTALogger.i("Preparing and validating APK: $apkPath")

                val prepared = localUpdateManager.prepareAPKForInstallation(apkPath)
                if (!prepared) {
                    _uiState.value = UIState.Error("Failed to prepare APK")
                    return@launch
                }

                val validated = localUpdateManager.validateAPK(apkPath)
                if (validated) {
                    _uiState.value = UIState.ReadyToInstall
                    OTALogger.i("APK validated successfully")
                } else {
                    _uiState.value = UIState.Error("APK validation failed")
                    OTALogger.e("APK validation failed")
                }
            } catch (e: Exception) {
                OTALogger.e("Error validating APK", e)
                _uiState.value = UIState.Error("Validation error: ${e.message}")
            }
        }
    }

    /**
     * Installs the APK
     */
    fun installUpdate(apkPath: String) {
        try {
            _uiState.value = UIState.Installing
            OTALogger.i("Installing APK: $apkPath")

            val success = localUpdateManager.installAPK(apkPath)

            if (success) {
                _uiState.value = UIState.Installing
                OTALogger.i("Install intent sent")
            } else {
                _uiState.value = UIState.Error("Failed to initiate installation")
            }
        } catch (e: Exception) {
            OTALogger.e("Error installing update", e)
            _uiState.value = UIState.Error("Installation error: ${e.message}")
        }
    }

    /**
     * Cancels the current operation
     */
    fun cancel() {
        _uiState.value = UIState.Idle
        _currentUpdateInfo.value = null
        OTALogger.i("Operation cancelled")
    }

    /**
     * Cleans up old files
     */
    fun cleanupOldUpdates() {
        viewModelScope.launch {
            try {
                localUpdateManager.cleanupOldUpdates()
                OTALogger.i("Cleanup completed")
            } catch (e: Exception) {
                OTALogger.e("Error cleaning up", e)
            }
        }
    }

    /**
     * Gets the installed version
     */
    private fun getInstalledVersion(): String {
        return try {
            val app = getApplication<Application>()
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageInfo(
                    app.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Gets APK storage path for local updates
     */
    fun getAPKStoragePath(): String {
        return android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ).absolutePath + "/ota_updates"
    }

    /**
     * Checks if APK files are available
     */
    fun hasAPKFilesAvailable(): Boolean {
        return try {
            val dir = File(getAPKStoragePath())
            if (!dir.exists()) dir.mkdirs()
            val apkFiles = dir.listFiles { file ->
                file.isFile && file.extension == "apk"
            } ?: emptyArray()
            apkFiles.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Possible UI states for Local OTA
 */
sealed class UIState {
    object Idle : UIState()
    object Checking : UIState()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UIState()
    data class NoUpdates(val reason: String = "") : UIState()
    object Verifying : UIState()
    object ReadyToInstall : UIState()
    object Installing : UIState()
    data class Error(val message: String) : UIState()
}

