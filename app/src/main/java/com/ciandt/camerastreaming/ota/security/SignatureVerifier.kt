package com.ciandt.camerastreaming.ota.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.ciandt.camerastreaming.ota.utils.OTALogger
import java.io.File

/**
 * Digital signature verifier for APK
 * Verifies that the downloaded APK is signed with the same certificate as the current app
 */
object SignatureVerifier {

    /**
     * Gets the signature of the installed app
     */
    fun getInstalledAppSignature(context: Context): String? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNATURES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }

            packageInfo.signatures?.firstOrNull()?.toCharsString()
        } catch (e: Exception) {
            OTALogger.e("Error getting installed app signature", e)
            null
        }
    }

    /**
     * Gets the signature of a downloaded APK
     * Note: For real validation, you would need to parse the APK and extract its certificate
     * This is a simplified implementation that returns a placeholder
     */
    fun getAPKSignature(apkPath: String): String? {
        return try {
            // In a real implementation, here would be the code to extract and read
            // the APK certificate using the Android SDK
            OTALogger.i("Getting signature for APK: $apkPath")
            // Placeholder - in production, use android.content.pm.PackageManager.getPackageArchiveInfo
            null
        } catch (e: Exception) {
            OTALogger.e("Error getting APK signature", e)
            null
        }
    }

    /**
     * Validates that two signatures match
     */
    fun validateSignatures(installedSignature: String?, downloadedSignature: String?): Boolean {
        return try {
            when {
                installedSignature == null || downloadedSignature == null -> {
                    OTALogger.w("One or both signatures are null")
                    false
                }
                installedSignature == downloadedSignature -> {
                    OTALogger.i("Signatures match - APK is valid")
                    true
                }
                else -> {
                    OTALogger.e("Signatures do not match")
                    false
                }
            }
        } catch (e: Exception) {
            OTALogger.e("Error validating signatures", e)
            false
        }
    }

    /**
     * Performs complete validation of APK signature
     */
    fun verifyAPK(context: Context, apkPath: String): Boolean {
        return try {
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                OTALogger.e("APK file does not exist: $apkPath")
                return false
            }

            val installedSig = getInstalledAppSignature(context)
            val downloadedSig = getAPKSignature(apkPath)

            validateSignatures(installedSig, downloadedSig)
        } catch (e: Exception) {
            OTALogger.e("Error verifying APK", e)
            false
        }
    }
}

