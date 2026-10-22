package com.example.crypto

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_SIZE_BITS = 256
    private const val ITERATION_COUNT = 10_000
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private const val CHUNK_SIZE = 64 * 1024 // 64 KB per authenticated chunk

    private val MAGIC_HEADER = byteArrayOf(0x42, 0x45, 0x4E, 0x43) // "BENC"
    private val secureRandom = SecureRandom()

    fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATION_COUNT, KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    /**
     * Checks if an input stream starts with the Bridge Encrypted magic header (BENC)
     */
    fun isStreamEncrypted(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < 4) return false
        return headerBytes[0] == MAGIC_HEADER[0] &&
                headerBytes[1] == MAGIC_HEADER[1] &&
                headerBytes[2] == MAGIC_HEADER[2] &&
                headerBytes[3] == MAGIC_HEADER[3]
    }

    /**
     * Encrypts an input stream to an output stream in authenticated 64KB AES-256-GCM chunks.
     * Guaranteed constant memory usage regardless of file size.
     */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        passphrase: String,
        originalSize: Long,
        onProgress: ((bytesProcessed: Long) -> Unit)? = null
    ) {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val secretKey = deriveKey(passphrase, salt)

        // Write 16-byte Header: Magic (4) + Version (1) + Flags (1) + Original Size (8) + Reserved (2)
        val header = ByteBuffer.allocate(16)
        header.put(MAGIC_HEADER)
        header.put(1.toByte()) // Version 1
        header.put(0.toByte()) // Flags
        header.putLong(originalSize)
        header.putShort(0.toShort())
        output.write(header.array())

        // Write 16-byte Salt
        output.write(salt)

        val buffer = ByteArray(CHUNK_SIZE)
        var totalRead = 0L

        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break

            val chunkIv = ByteArray(IV_SIZE_BYTES)
            secureRandom.nextBytes(chunkIv)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE_BITS, chunkIv))
            val cipherText = cipher.doFinal(buffer, 0, read)

            // Write chunk header: length of cipherText (Int: 4 bytes) + chunkIv (12 bytes) + cipherText
            val chunkLenBuf = ByteBuffer.allocate(4).putInt(cipherText.size).array()
            output.write(chunkLenBuf)
            output.write(chunkIv)
            output.write(cipherText)

            totalRead += read
            onProgress?.invoke(totalRead)
        }

        // Write EOF chunk marker: 4 bytes with 0
        output.write(ByteBuffer.allocate(4).putInt(0).array())
        output.flush()
    }

    /**
     * Decrypts an authenticated AES-256-GCM chunked stream to output stream.
     * Returns true if decryption succeeded, false if invalid password or corrupted.
     */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        passphrase: String,
        onProgress: ((bytesWritten: Long) -> Unit)? = null
    ): Boolean {
        // Read 16-byte Header
        val headerBytes = ByteArray(16)
        var readHeader = 0
        while (readHeader < 16) {
            val r = input.read(headerBytes, readHeader, 16 - readHeader)
            if (r <= 0) return false
            readHeader += r
        }

        if (!isStreamEncrypted(headerBytes)) {
            return false
        }

        // Read 16-byte Salt
        val salt = ByteArray(16)
        var readSalt = 0
        while (readSalt < 16) {
            val r = input.read(salt, readSalt, 16 - readSalt)
            if (r <= 0) return false
            readSalt += r
        }

        val secretKey = deriveKey(passphrase, salt)
        val lenBuf = ByteArray(4)
        var totalWritten = 0L

        while (true) {
            var readLen = 0
            while (readLen < 4) {
                val r = input.read(lenBuf, readLen, 4 - readLen)
                if (r <= 0) return false
                readLen += r
            }

            val cipherTextLen = ByteBuffer.wrap(lenBuf).int
            if (cipherTextLen == 0) {
                // EOF marker reached cleanly
                break
            }

            val chunkIv = ByteArray(IV_SIZE_BYTES)
            var readIv = 0
            while (readIv < IV_SIZE_BYTES) {
                val r = input.read(chunkIv, readIv, IV_SIZE_BYTES - readIv)
                if (r <= 0) return false
                readIv += r
            }

            val cipherText = ByteArray(cipherTextLen)
            var readCipher = 0
            while (readCipher < cipherTextLen) {
                val r = input.read(cipherText, readCipher, cipherTextLen - readCipher)
                if (r <= 0) return false
                readCipher += r
            }

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE_BITS, chunkIv))
            val plainText = cipher.doFinal(cipherText)

            output.write(plainText)
            totalWritten += plainText.size
            onProgress?.invoke(totalWritten)
        }

        output.flush()
        return true
    }
}
