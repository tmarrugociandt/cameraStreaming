package com.ciandt.camerastreaming.ota.network

import android.content.Context
import com.ciandt.camerastreaming.ota.models.UpdateInfo
import com.ciandt.camerastreaming.ota.utils.OTAConstants
import com.ciandt.camerastreaming.ota.utils.OTALogger
import java.io.File

/**
 * Local OTA provider for loading APK updates from local device storage
 * Provides an alternative to remote OTA updates from a server
 */
class LocalOTAProvider(private val context: Context) {

    /**
     * Searches for APK files in a given directory
     * @param directoryPath Path to search for APK files
     * @return List of APK files found
     */
    fun findApkFiles(directoryPath: String): List<File> {
        return try {
            val directory = File(directoryPath)

            OTALogger.i("========== APK SEARCH START ==========")
            OTALogger.i("Searching for APK files in: $directoryPath")
            OTALogger.i("Directory exists: ${directory.exists()}")
            OTALogger.i("Is directory: ${directory.isDirectory}")
            OTALogger.i("Directory readable: ${directory.canRead()}")
            OTALogger.i("Directory absolute path: ${directory.absolutePath}")

            if (!directory.exists() || !directory.isDirectory) {
                OTALogger.e("Directory does not exist or is not a directory: $directoryPath")
                return emptyList()
            }

            if (!directory.canRead()) {
                OTALogger.e("No permission to read directory: $directoryPath")
                return emptyList()
            }

            // Try multiple ways to list files
            OTALogger.i("Attempting to list files...")

            // Method 1: listFiles()
            val allFiles = directory.listFiles()
            OTALogger.i("listFiles() returned: ${allFiles?.size ?: "null"} files")
            if (allFiles != null) {
                allFiles.forEach { file ->
                    OTALogger.i("  listFiles: ${file.name} (isFile: ${file.isFile}, readable: ${file.canRead()})")
                }
            }

            // Method 2: list()
            val fileNames = directory.list()
            OTALogger.i("list() returned: ${fileNames?.size ?: "null"} items")
            if (fileNames != null) {
                fileNames.forEach { name ->
                    OTALogger.i("  - $name")
                    // Try to access the file to verify it exists
                    val file = File(directory, name)
                    OTALogger.i("    File exists: ${file.exists()}, isFile: ${file.isFile}, readable: ${file.canRead()}")
                }
            }

            // Method 3: walk
            val walkFiles = directory.walk().filter { it.isFile }.toList()
            OTALogger.i("walk() returned: ${walkFiles.size} files")
            walkFiles.forEach { file ->
                OTALogger.i("  - ${file.name}")
            }

            val filesArray = allFiles ?: emptyArray()
            OTALogger.i("Total files in directory: ${filesArray.size}")

            if (filesArray.isEmpty()) {
                OTALogger.w("Directory appears empty")
                // Try using list() as fallback
                if (fileNames != null && fileNames.isNotEmpty()) {
                    OTALogger.i("Using list() results instead of listFiles()")
                    fileNames.forEach { fileName ->
                        val file = File(directory, fileName)
                        if (file.isFile) {
                            OTALogger.i("Added from list(): ${file.name}")
                        }
                    }
                } else {
                    OTALogger.i("Trying to create test file to verify write permissions...")
                    try {
                        val testFile = File(directory, "test.txt")
                        testFile.createNewFile()
                        OTALogger.i("Test file created successfully - directory is writable")
                        testFile.delete()
                    } catch (e: Exception) {
                        OTALogger.e("Cannot create test file: ${e.message}")
                    }
                }
            }

            filesArray.forEach { file ->
                OTALogger.i("Found file: ${file.name} (isFile: ${file.isFile}, extension: ${file.extension}, size: ${file.length()}, readable: ${file.canRead()})")
            }

            val apkFiles = mutableListOf<File>()
            filesArray.forEach { file ->
                val isApk = file.isFile && file.extension.equals("apk", ignoreCase = true)
                val isNotTemp = !file.isDownloadInProgress()
                val isReadable = file.canRead()

                OTALogger.i("Checking file: ${file.name} - isFile: ${file.isFile}, isApk: $isApk, isNotTemp: $isNotTemp, isReadable: $isReadable")

                if (isApk && isNotTemp && isReadable) {
                    OTALogger.i("✓ ACCEPTED: ${file.name}")
                    apkFiles.add(file)
                }
            }

            // Fallback: if listFiles() returned nothing but list() returned items, try again with list()
            if (apkFiles.isEmpty() && fileNames != null && fileNames.isNotEmpty()) {
                OTALogger.i("Fallback: Using list() to find APK files")
                fileNames.forEach { fileName ->
                    val file = File(directory, fileName)
                    if (file.isFile && file.extension.equals("apk", ignoreCase = true) && file.canRead()) {
                        OTALogger.i("✓ FALLBACK ACCEPTED: ${file.name}")
                        apkFiles.add(file)
                    }
                }
            }

            OTALogger.i("Found ${apkFiles.size} APK files")
            apkFiles.forEach { apk ->
                OTALogger.i("APK: ${apk.name} - Size: ${apk.length()} bytes - Path: ${apk.absolutePath}")
            }
            OTALogger.i("========== APK SEARCH END ==========")

            apkFiles
        } catch (e: Exception) {
            OTALogger.e("Error searching for APK files: ${e.message}", e)
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Loads update info from a local APK file
     * @param apkPath Full path to the APK file
     * @return UpdateInfo object with the APK details
     */
    fun loadUpdateInfoFromAPK(apkPath: String): Result<UpdateInfo> {
        return try {
            val apkFile = File(apkPath)

            if (!apkFile.exists()) {
                return Result.failure(Exception("APK file does not exist: $apkPath"))
            }

            if (!apkFile.isFile) {
                return Result.failure(Exception("Path is not a file: $apkPath"))
            }

            if (!apkFile.extension.equals("apk", ignoreCase = true)) {
                return Result.failure(Exception("File is not an APK: $apkPath"))
            }

            val fileSize = apkFile.length()
            val fileName = apkFile.name
            val lastModified = apkFile.lastModified()

            // Create UpdateInfo with local path instead of URL
            val updateInfo = UpdateInfo(
                versionCode = 0, // Will be extracted from APK manifest if needed
                versionName = "Local Update",
                description = "Local APK file: $fileName",
                releaseDate = java.time.Instant.ofEpochMilli(lastModified).toString(),
                apkUrl = apkPath, // Store the local path
                fileSize = fileSize,
                releaseNotes = "Update from local storage: $fileName",
                isMandatory = false,
                isLocalSource = true
            )

            OTALogger.i("Loaded update info from $apkPath - size: $fileSize bytes")
            Result.success(updateInfo)
        } catch (e: Exception) {
            OTALogger.e("Error loading update info from APK", e)
            Result.failure(e)
        }
    }

    /**
     * Validates if a local APK file is accessible and valid
     * @param apkPath Path to the APK file
     * @return Boolean indicating if the file is valid
     */
    fun validateLocalAPK(apkPath: String): Boolean {
        return try {
            val apkFile = File(apkPath)
            val isValid = apkFile.exists() && apkFile.isFile && apkFile.canRead()

            if (!isValid) {
                OTALogger.e("Invalid or inaccessible APK file: $apkPath")
            } else {
                OTALogger.i("Local APK file validation passed: $apkPath")
            }

            isValid
        } catch (e: Exception) {
            OTALogger.e("Error validating local APK", e)
            false
        }
    }

    /**
     * Copies a local APK file to the OTA update directory
     * @param sourcePath Full path to the source APK
     * @return Path to the copied file or null if failed
     */
    fun copyAPKToUpdateDirectory(sourcePath: String): Result<String> {
        return try {
            val sourceFile = File(sourcePath)

            if (!sourceFile.exists() || !sourceFile.isFile) {
                return Result.failure(Exception("Source APK file does not exist: $sourcePath"))
            }

            val targetFile = File(OTAConstants.getOTADirectory(context), OTAConstants.APK_FILE_NAME)

            // Delete existing file if present
            if (targetFile.exists()) {
                targetFile.delete()
            }

            // Copy file
            sourceFile.copyTo(targetFile, overwrite = true)

            OTALogger.i("APK copied from $sourcePath to ${targetFile.absolutePath}")
            Result.success(targetFile.absolutePath)
        } catch (e: Exception) {
            OTALogger.e("Error copying APK to update directory", e)
            Result.failure(e)
        }
    }

    /**
     * Gets file information for a local APK
     * @param apkPath Path to the APK file
     * @return Map containing file information or empty map if error
     */
    fun getAPKFileInfo(apkPath: String): Map<String, Any> {
        return try {
            val apkFile = File(apkPath)

            if (!apkFile.exists()) {
                OTALogger.e("APK file does not exist: $apkPath")
                return emptyMap()
            }

            mapOf(
                "name" to apkFile.name,
                "path" to apkFile.absolutePath,
                "size" to apkFile.length(),
                "sizeInMB" to (apkFile.length() / (1024.0 * 1024.0)),
                "lastModified" to apkFile.lastModified(),
                "canRead" to apkFile.canRead(),
                "canWrite" to apkFile.canWrite(),
                "parentDirectory" to (apkFile.parentFile?.absolutePath ?: "Unknown")
            )
        } catch (e: Exception) {
            OTALogger.e("Error getting APK file info", e)
            emptyMap()
        }
    }

    /**
     * Extension function to check if a file download is in progress
     */
    private fun File.isDownloadInProgress(): Boolean {
        return this.name.endsWith(".tmp") || this.name.endsWith(".part")
    }
}

