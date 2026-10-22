package com.example.transfer

import android.content.Context
import com.example.crypto.CryptoEngine
import com.example.model.SharedFile
import com.example.model.TransferDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RemotePeerSession(
    val sessionId: String,
    val deviceName: String,
    val avatarId: String,
    val isEncrypted: Boolean,
    val fileCount: Int
)

class BridgeClient(
    private val context: Context,
    private val storageManager: StorageManager
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for streaming
        .build()

    suspend fun fetchSessionInfo(serverUrl: String, token: String = ""): Result<RemotePeerSession> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = serverUrl.trimEnd('/')
            val endpoint = if (token.isNotEmpty()) "$cleanBase/api/session?token=$token" else "$cleanBase/api/session"
            val request = Request.Builder().url(endpoint).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
            val json = JSONObject(body)
            Result.success(
                RemotePeerSession(
                    sessionId = json.optString("sessionId", ""),
                    deviceName = json.optString("deviceName", "Bridge Device"),
                    avatarId = json.optString("avatarId", "cyber_falcon"),
                    isEncrypted = json.optBoolean("isEncrypted", true),
                    fileCount = json.optInt("fileCount", 0)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchRemoteFiles(serverUrl: String, token: String): Result<List<SharedFile>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = serverUrl.trimEnd('/')
            val endpoint = if (token.isNotEmpty()) "$cleanBase/api/files?token=$token" else "$cleanBase/api/files"
            val request = Request.Builder().url(endpoint).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
            val json = JSONObject(body)
            val filesArray = json.optJSONArray("files") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<SharedFile>()
            for (i in 0 until filesArray.length()) {
                val obj = filesArray.getJSONObject(i)
                list.add(
                    SharedFile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        size = obj.getLong("size"),
                        mimeType = obj.optString("mimeType", "application/octet-stream")
                    )
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(
        serverUrl: String,
        token: String,
        file: SharedFile,
        passphrase: String = "",
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanBase = serverUrl.trimEnd('/')
        val downloadUrl = if (token.isNotEmpty()) {
            "$cleanBase/api/download/${file.id}?token=$token"
        } else {
            "$cleanBase/api/download/${file.id}"
        }

        val destinationFile = storageManager.createDestinationFile(file.name)
        val transferId = UUID.randomUUID().toString()

        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                TransferManager.startTransfer(
                    id = transferId,
                    fileId = file.id,
                    fileName = file.name,
                    totalBytes = file.size,
                    direction = TransferDirection.DOWNLOAD,
                    peerAddress = serverUrl,
                    isEncrypted = false
                )
                TransferManager.failTransfer(transferId, "HTTP ${response.code}")
                return@withContext Result.failure(Exception("Failed to download: HTTP ${response.code}"))
            }

            val isEncryptedHeader = response.header("X-Encrypted")?.equals("true", ignoreCase = true) == true
            val body = response.body ?: throw Exception("Response body null")
            val totalBytes = if (file.size > 0) file.size else body.contentLength()

            TransferManager.startTransfer(
                id = transferId,
                fileId = file.id,
                fileName = file.name,
                totalBytes = totalBytes,
                direction = TransferDirection.DOWNLOAD,
                peerAddress = serverUrl,
                isEncrypted = isEncryptedHeader
            )

            val rawStream = body.byteStream()
            val bufferedIn = BufferedInputStream(rawStream, 64 * 1024)

            // Peek 4 bytes to check if stream has BENC magic header
            bufferedIn.mark(16)
            val peekHeader = ByteArray(4)
            val peekRead = bufferedIn.read(peekHeader, 0, 4)
            bufferedIn.reset()

            val hasEncryptedMagic = (peekRead == 4 && CryptoEngine.isStreamEncrypted(peekHeader))
            val shouldDecrypt = isEncryptedHeader || hasEncryptedMagic

            val effectiveKey = if (passphrase.isNotBlank()) passphrase else token

            if (shouldDecrypt) {
                // Decrypt authenticated AES-256-GCM chunked stream
                FileOutputStream(destinationFile).use { outStream ->
                    val ok = CryptoEngine.decryptStream(
                        input = bufferedIn,
                        output = outStream,
                        passphrase = effectiveKey,
                        onProgress = { bytesWritten ->
                            if (TransferManager.isCancelled(transferId)) {
                                destinationFile.delete()
                                TransferManager.cancelTransfer(transferId)
                                return@decryptStream
                            }
                            TransferManager.updateProgress(transferId, bytesWritten)
                            onProgress?.invoke(bytesWritten, totalBytes)
                        }
                    )

                    if (!ok) {
                        destinationFile.delete()
                        TransferManager.failTransfer(transferId, "AES-256 Authentication Failed (Key Mismatch or Corrupted)")
                        return@withContext Result.failure(Exception("Decryption authentication failed"))
                    }
                }
            } else {
                // Plain stream copy
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesCopied = 0L
                    var read: Int

                    while (bufferedIn.read(buffer).also { read = it } != -1) {
                        if (TransferManager.isCancelled(transferId)) {
                            destinationFile.delete()
                            TransferManager.cancelTransfer(transferId)
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }
                        while (TransferManager.isPaused(transferId)) {
                            Thread.sleep(200)
                        }

                        output.write(buffer, 0, read)
                        bytesCopied += read
                        TransferManager.updateProgress(transferId, bytesCopied)
                        onProgress?.invoke(bytesCopied, totalBytes)
                    }
                    output.flush()
                }
            }

            TransferManager.completeTransfer(transferId)
            storageManager.notifyMediaStore(destinationFile)
            Result.success(destinationFile)
        } catch (e: Exception) {
            destinationFile.delete()
            TransferManager.failTransfer(transferId, e.message ?: "Download failed")
            Result.failure(e)
        }
    }
}
