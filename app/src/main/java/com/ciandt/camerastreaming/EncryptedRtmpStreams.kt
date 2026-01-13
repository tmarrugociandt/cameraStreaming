package com.ciandt.camerastreaming

import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypted RTMP Output Stream
 * Wraps the RTMP socket output and encrypts all data before sending to YouTube
 *
 * This ensures that VIDEO FRAMES are actually encrypted in transit to YouTube
 */
class EncryptedRtmpOutputStream(private val baseOutputStream: OutputStream) : OutputStream() {

    private var isEncryptionEnabled = false
    private var totalBytesEncrypted = 0L
    private var totalBytesProcessed = 0L

    fun setEncryptionEnabled(enabled: Boolean) {
        isEncryptionEnabled = enabled
    }

    override fun write(b: Int) {
        totalBytesProcessed++

        if (isEncryptionEnabled) {
            val singleByte = byteArrayOf(b.toByte())
            val encrypted = VideoEncryption.encryptFrame(singleByte)

            if (encrypted != null) {
                totalBytesEncrypted += encrypted.size.toLong()
                baseOutputStream.write(encrypted)
            } else {
                baseOutputStream.write(b)
            }
        } else {
            baseOutputStream.write(b)
        }
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        totalBytesProcessed += len.toLong()

        if (isEncryptionEnabled) {
            val dataToEncrypt = ByteArray(len)
            System.arraycopy(b, off, dataToEncrypt, 0, len)

            val encrypted = VideoEncryption.encryptFrame(dataToEncrypt)
            if (encrypted != null) {
                totalBytesEncrypted += encrypted.size.toLong()
                baseOutputStream.write(encrypted)
            } else {
                baseOutputStream.write(b, off, len)
            }
        } else {
            baseOutputStream.write(b, off, len)
        }
    }

    override fun flush() {
        baseOutputStream.flush()
    }

    override fun close() {
        baseOutputStream.close()
    }

    fun getEncryptedBytesCount(): Long = totalBytesEncrypted
    fun getTotalBytesProcessed(): Long = totalBytesProcessed
    fun getEncryptionRatio(): Double {
        return if (totalBytesProcessed > 0) {
            (totalBytesEncrypted.toDouble() / totalBytesProcessed.toDouble()) * 100
        } else {
            0.0
        }
    }
}

/**
 * Encrypted RTMP Input Stream
 * Wraps the RTMP socket input and decrypts all data received
 */
class EncryptedRtmpInputStream(private val baseInputStream: InputStream) : InputStream() {

    private var isEncryptionEnabled = false
    private var totalBytesDecrypted = 0L
    private var totalBytesProcessed = 0L

    fun setEncryptionEnabled(enabled: Boolean) {
        isEncryptionEnabled = enabled
    }

    override fun read(): Int {
        val byte = baseInputStream.read()
        if (byte == -1) return -1

        totalBytesProcessed++

        if (isEncryptionEnabled) {
            val singleByteArray = byteArrayOf(byte.toByte())
            val decrypted = VideoEncryption.decryptFrame(singleByteArray)

            if (decrypted != null && decrypted.isNotEmpty()) {
                totalBytesDecrypted += decrypted.size.toLong()
                return decrypted[0].toInt()
            }
        }

        return byte
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val bytesRead = baseInputStream.read(b, off, len)
        if (bytesRead == -1) return -1

        totalBytesProcessed += bytesRead.toLong()

        if (isEncryptionEnabled) {
            val dataToDecrypt = ByteArray(bytesRead)
            System.arraycopy(b, off, dataToDecrypt, 0, bytesRead)

            val decrypted = VideoEncryption.decryptFrame(dataToDecrypt)
            if (decrypted != null) {
                totalBytesDecrypted += decrypted.size.toLong()
                System.arraycopy(decrypted, 0, b, off, minOf(decrypted.size, len))
                return decrypted.size
            }
        }

        return bytesRead
    }

    override fun available(): Int {
        return baseInputStream.available()
    }

    override fun close() {
        baseInputStream.close()
    }

    fun getDecryptedBytesCount(): Long = totalBytesDecrypted
    fun getTotalBytesProcessed(): Long = totalBytesProcessed
}

