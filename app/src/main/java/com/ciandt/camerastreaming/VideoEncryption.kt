package com.ciandt.camerastreaming

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles video frame encryption/decryption using AES-256-GCM
 * Provides end-to-end encryption for video data in transit
 */
object VideoEncryption {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256  // AES-256
    private const val GCM_TAG_LENGTH = 128  // bits
    private const val IV_LENGTH = 12  // bytes (96 bits recommended for GCM)
    private const val SALT_LENGTH = 16  // bytes

    private var encryptionKey: SecretKey? = null
    private var isInitialized = false

    /**
     * Initialize encryption with a master password
     * Derives a key from the password using PBKDF2-like approach
     */
    fun initialize(masterPassword: String): Boolean {
        return try {
            encryptionKey = deriveKeyFromPassword(masterPassword)
            isInitialized = true
            Log.i("VideoEncryption", "Encryption initialized successfully")
            true
        } catch (e: Exception) {
            Log.e("VideoEncryption", "Failed to initialize encryption: ${e.message}")
            false
        }
    }

    /**
     * Generate a new random encryption key (for testing/one-time use)
     */
    fun generateNewKey(): Boolean {
        return try {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(KEY_SIZE)
            encryptionKey = keyGen.generateKey()
            isInitialized = true
            Log.i("VideoEncryption", "New encryption key generated")
            true
        } catch (e: Exception) {
            Log.e("VideoEncryption", "Failed to generate key: ${e.message}")
            false
        }
    }

    /**
     * Get the current encryption key as Base64 (for sharing/backup)
     */
    fun getKeyAsBase64(): String? {
        return encryptionKey?.let { Base64.encodeToString(it.encoded, Base64.DEFAULT) }
    }

    /**
     * Set encryption key from Base64 string
     */
    fun setKeyFromBase64(keyBase64: String): Boolean {
        return try {
            val decodedKey = Base64.decode(keyBase64, Base64.DEFAULT)
            encryptionKey = SecretKeySpec(decodedKey, 0, decodedKey.size, "AES")
            isInitialized = true
            Log.i("VideoEncryption", "Encryption key loaded from Base64")
            true
        } catch (e: Exception) {
            Log.e("VideoEncryption", "Failed to load key from Base64: ${e.message}")
            false
        }
    }

    /**
     * Encrypt video frame data
     * Returns encrypted data with IV prepended for decryption
     */
    fun encryptFrame(plainData: ByteArray): ByteArray? {
        if (!isInitialized || encryptionKey == null) {
            Log.e("VideoEncryption", "Encryption not initialized")
            return null
        }

        return try {
            // Generate random IV for this frame
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().nextBytes(iv)

            // Create cipher
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, gcmParameterSpec)

            // Encrypt the data
            val encryptedData = cipher.doFinal(plainData)

            // Prepend IV to encrypted data (IV is 12 bytes)
            val result = ByteArray(IV_LENGTH + encryptedData.size)
            System.arraycopy(iv, 0, result, 0, IV_LENGTH)
            System.arraycopy(encryptedData, 0, result, IV_LENGTH, encryptedData.size)

            Log.d("VideoEncryption", "Frame encrypted: ${plainData.size} bytes → ${result.size} bytes")
            result
        } catch (e: Exception) {
            Log.e("VideoEncryption", "Encryption failed: ${e.message}")
            null
        }
    }

    /**
     * Decrypt video frame data
     * Expects IV prepended to encrypted data
     */
    fun decryptFrame(encryptedData: ByteArray): ByteArray? {
        if (!isInitialized || encryptionKey == null) {
            Log.e("VideoEncryption", "Encryption not initialized")
            return null
        }

        if (encryptedData.size < IV_LENGTH) {
            Log.e("VideoEncryption", "Invalid encrypted data: too small")
            return null
        }

        return try {
            // Extract IV from encrypted data
            val iv = ByteArray(IV_LENGTH)
            System.arraycopy(encryptedData, 0, iv, 0, IV_LENGTH)

            // Extract encrypted content
            val encryptedContent = ByteArray(encryptedData.size - IV_LENGTH)
            System.arraycopy(encryptedData, IV_LENGTH, encryptedContent, 0, encryptedContent.size)

            // Create cipher
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, gcmParameterSpec)

            // Decrypt the data
            val decryptedData = cipher.doFinal(encryptedContent)

            Log.d("VideoEncryption", "Frame decrypted: ${encryptedData.size} bytes → ${decryptedData.size} bytes")
            decryptedData
        } catch (e: Exception) {
            Log.e("VideoEncryption", "Decryption failed: ${e.message}")
            null
        }
    }

    /**
     * Encrypt data to Base64 string (for transmission/storage)
     */
    fun encryptToBase64(plainData: ByteArray): String? {
        return encryptFrame(plainData)?.let {
            Base64.encodeToString(it, Base64.DEFAULT)
        }
    }

    /**
     * Decrypt data from Base64 string
     */
    fun decryptFromBase64(encryptedBase64: String): ByteArray? {
        return try {
            val encryptedData = Base64.decode(encryptedBase64, Base64.DEFAULT)
            decryptFrame(encryptedData)
        } catch (e: Exception) {
            Log.e("VideoEncryption", "Failed to decode Base64: ${e.message}")
            null
        }
    }

    /**
     * Derive encryption key from password using simple key derivation
     * In production, use PBKDF2 for stronger derivation
     */
    private fun deriveKeyFromPassword(password: String): SecretKey {
        // Simple approach: use SHA-256 hash of password
        // In production, use PBKDF2 or Argon2 for better security
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val key = digest.digest(password.toByteArray(Charsets.UTF_8))

        return SecretKeySpec(key, 0, key.size, "AES")
    }

    /**
     * Get encryption statistics
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "initialized" to isInitialized,
            "keySize" to KEY_SIZE,
            "algorithm" to ALGORITHM,
            "ivLength" to IV_LENGTH,
            "gcmTagLength" to GCM_TAG_LENGTH
        )
    }

    /**
     * Clear sensitive data (call on app exit)
     */
    fun clear() {
        encryptionKey = null
        isInitialized = false
        Log.i("VideoEncryption", "Encryption resources cleared")
    }
}

