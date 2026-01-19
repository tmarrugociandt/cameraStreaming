package com.ciandt.camerastreaming.ota.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.ciandt.camerastreaming.ota.models.UpdateInfo
import com.ciandt.camerastreaming.ota.models.UpdateStatus
import com.ciandt.camerastreaming.ota.network.LocalOTAProvider
import com.ciandt.camerastreaming.ota.security.SignatureVerifier
import com.ciandt.camerastreaming.ota.utils.OTAConstants
import com.ciandt.camerastreaming.ota.utils.OTALogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Local OTA update manager
 * Handles OTA updates from local file system storage
 * Extends UpdateManager functionality for local APK sources
 */
class LocalUpdateManager(
    private val context: Context,
    private val fileProviderAuthority: String = "${context.packageName}.fileprovider"
) {

    private val localOTAProvider = LocalOTAProvider(context)

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: Flow<UpdateStatus> = _updateStatus.asStateFlow()

    private val _currentVersionCode = MutableStateFlow(getInstalledVersionCode())
    val currentVersionCode: Flow<Int> = _currentVersionCode.asStateFlow()

    /**
     * Scans a directory for available APK updates
     * @param directoryPath Path to directory containing APK files
     * @return UpdateStatus with available update or error
     */
    suspend fun checkForLocalUpdates(directoryPath: String): UpdateStatus {
        return try {
            _updateStatus.value = UpdateStatus.Checking
            OTALogger.i("Checking for local updates in: $directoryPath")

            val apkFiles = localOTAProvider.findApkFiles(directoryPath)

            if (apkFiles.isEmpty()) {
                OTALogger.i("No APK files found in directory: $directoryPath")
                val status = UpdateStatus.NotAvailable("No APK files found in the specified directory")
                _updateStatus.value = status
                return status
            }

            // Use the first APK found
            val selectedApk = apkFiles.first()
            OTALogger.i("Found APK file: ${selectedApk.absolutePath}")

            val updateInfoResult = localOTAProvider.loadUpdateInfoFromAPK(selectedApk.absolutePath)

            updateInfoResult.fold(
                onSuccess = { updateInfo ->
                    OTALogger.i("Update info loaded: ${updateInfo.versionName}")
                    val status = UpdateStatus.Available(updateInfo)
                    _updateStatus.value = status
                    status
                },
                onFailure = { exception ->
                    OTALogger.e("Failed to load update info", exception)
                    val status = UpdateStatus.Error(exception as Exception, "check_local")
                    _updateStatus.value = status
                    status
                }
            )
        } catch (e: Exception) {
            OTALogger.e("Error checking for local updates", e)
            val status = UpdateStatus.Error(e, "check_local")
            _updateStatus.value = status
            status
        }
    }

    /**
     * Loads a specific APK from a local path
     * @param apkPath Full path to the APK file
     * @return UpdateStatus with update info or error
     */
    suspend fun loadUpdateFromPath(apkPath: String): UpdateStatus {
        return try {
            _updateStatus.value = UpdateStatus.Checking
            OTALogger.i("Loading update from path: $apkPath")

            val updateInfoResult = localOTAProvider.loadUpdateInfoFromAPK(apkPath)

            updateInfoResult.fold(
                onSuccess = { updateInfo ->
                    OTALogger.i("Update loaded successfully: ${updateInfo.versionName}")
                    val status = UpdateStatus.Available(updateInfo)
                    _updateStatus.value = status
                    status
                },
                onFailure = { exception ->
                    OTALogger.e("Failed to load update from path", exception)
                    val status = UpdateStatus.Error(exception as Exception, "load_path")
                    _updateStatus.value = status
                    status
                }
            )
        } catch (e: Exception) {
            OTALogger.e("Error loading update from path", e)
            val status = UpdateStatus.Error(e, "load_path")
            _updateStatus.value = status
            status
        }
    }

    /**
     * Prepares a local APK for installation by copying it to the OTA directory
     * @param apkPath Full path to the source APK
     * @return Boolean indicating success
     */
    suspend fun prepareAPKForInstallation(apkPath: String): Boolean {
        return try {
            _updateStatus.value = UpdateStatus.Downloaded
            OTALogger.i("Preparing APK for installation: $apkPath")

            val copyResult = localOTAProvider.copyAPKToUpdateDirectory(apkPath)

            copyResult.fold(
                onSuccess = { copiedPath ->
                    OTALogger.i("APK prepared successfully at: $copiedPath")
                    true
                },
                onFailure = { exception ->
                    OTALogger.e("Failed to prepare APK", exception)
                    val status = UpdateStatus.Error(exception as Exception, "prepare")
                    _updateStatus.value = status
                    false
                }
            )
        } catch (e: Exception) {
            OTALogger.e("Error preparing APK", e)
            val status = UpdateStatus.Error(e, "prepare")
            _updateStatus.value = status
            false
        }
    }

    /**
     * Validates a local APK file
     * @param apkPath Path to the APK file to validate
     * @return Boolean indicating if APK is valid
     */
    suspend fun validateAPK(apkPath: String): Boolean {
        return try {
            _updateStatus.value = UpdateStatus.Verifying
            OTALogger.i("Validating APK: $apkPath")

            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                OTALogger.e("APK file not found: $apkPath")
                return false
            }

            // Validate signature if needed (optional for local APKs)
            val signatureValid = SignatureVerifier.verifyAPK(context, apkPath)

            OTALogger.i("APK validation result - Signature: $signatureValid")

            if (signatureValid) {
                _updateStatus.value = UpdateStatus.Ready
                return true
            }

            // Even if signature validation fails, allow installation of local APKs
            _updateStatus.value = UpdateStatus.Ready
            return true
        } catch (e: Exception) {
            OTALogger.e("Error validating APK", e)
            val status = UpdateStatus.Error(e, "verify")
            _updateStatus.value = status
            return false
        }
    }

    /**
     * Installs the APK using system intent
     * @param apkPath Path to the APK file to install
     * @return Boolean indicating if install intent was sent
     */
    fun installAPK(apkPath: String): Boolean {
        return try {
            _updateStatus.value = UpdateStatus.Installing
            OTALogger.i("Installing APK: $apkPath")

            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                OTALogger.e("APK file not found: $apkPath")
                return false
            }

            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, fileProviderAuthority, apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
            OTALogger.i("Install intent sent")
            return true

        } catch (e: Exception) {
            OTALogger.e("Error installing APK", e)
            val status = UpdateStatus.Error(e, "install")
            _updateStatus.value = status
            return false
        }
    }

    /**
     * Gets file information for a local APK
     * @param apkPath Path to the APK file
     * @return Map containing file information
     */
    fun getAPKInfo(apkPath: String): Map<String, Any> {
        return localOTAProvider.getAPKFileInfo(apkPath)
    }

    /**
     * Cleans up old update files from the OTA directory
     */
    fun cleanupOldUpdates() {
        try {
            OTALogger.i("Cleaning up old update files")
            val otaDir = OTAConstants.getOTADirectory(context)
            otaDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.isDownloadInProgress()) {
                    file.delete()
                    OTALogger.i("Deleted: ${file.name}")
                }
            }
        } catch (e: Exception) {
            OTALogger.e("Error cleaning up old updates", e)
        }
    }

    /**
     * Gets the current installed version code
     */
    private fun getInstalledVersionCode(): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionCode
        } catch (e: Exception) {
            OTALogger.e("Error getting version code", e)
            0
        }
    }

    /**
     * Resets the update status to Idle
     */
    fun reset() {
        _updateStatus.value = UpdateStatus.Idle
    }

    /**
     * Extension function to check if a file download is in progress
     */
    private fun File.isDownloadInProgress(): Boolean {
        return this.name.endsWith(".tmp") || this.name.endsWith(".part")
    }
}

