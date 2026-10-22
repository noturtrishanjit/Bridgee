package com.example.transfer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.model.SharedFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class StorageManager(private val context: Context) {

    /**
     * Resolves metadata for an Android Uri (e.g. from file picker or share intent).
     */
    fun resolveSharedFile(uri: Uri): SharedFile? {
        val contentResolver = context.contentResolver
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        cursor.getString(nameIndex)?.let { name = it }
                    }
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If size is still 0, try available bytes or parcel file descriptor
        if (size <= 0) {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (ignored: Exception) {}
        }

        val extension = MimeTypeMap.getFileExtensionFromUrl(name)
        if (mimeType == "application/octet-stream" && extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())?.let {
                mimeType = it
            }
        }

        return SharedFile(
            id = UUID.randomUUID().toString(),
            name = name,
            size = size,
            mimeType = mimeType,
            uriString = uri.toString(),
            isUploadedByPeer = false
        )
    }

    /**
     * Opens an input stream for reading a shared file.
     */
    fun openInputStream(sharedFile: SharedFile): InputStream? {
        if (!sharedFile.filePath.isNullOrEmpty()) {
            val file = File(sharedFile.filePath)
            if (file.exists()) {
                return file.inputStream()
            }
        }
        if (!sharedFile.uriString.isNullOrEmpty()) {
            return context.contentResolver.openInputStream(Uri.parse(sharedFile.uriString))
        }
        return null
    }

    /**
     * Guesses MIME type from filename extension.
     */
    fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    /**
     * Scans received file with Android MediaStore so it appears immediately
     * in the Gallery/Photos app and Downloads.
     */
    fun notifyMediaStore(file: File) {
        if (!file.exists()) return
        val mime = guessMimeType(file.name)
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mime),
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Returns all previously and newly received files stored across Bridge directories.
     */
    fun getReceivedFiles(): List<SharedFile> {
        val candidateDirs = listOfNotNull(
            try { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bridge") } catch (e: Exception) { null },
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "Bridge") },
            File(context.filesDir, "Bridge")
        ).distinct()

        val allFiles = mutableListOf<File>()
        for (dir in candidateDirs) {
            try {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 }?.let {
                        allFiles.addAll(it)
                    }
                }
            } catch (ignored: Exception) {}
        }

        return allFiles
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                SharedFile(
                    id = file.absolutePath.hashCode().toString(),
                    name = file.name,
                    size = file.length(),
                    mimeType = guessMimeType(file.name),
                    filePath = file.absolutePath,
                    isUploadedByPeer = true
                )
            }
    }

    /**
     * Directly opens an image or file with the default system application (Gallery, PDF viewer, etc.)
     */
    fun openFile(sharedFile: SharedFile): Result<Unit> {
        val file = sharedFile.filePath?.let { File(it) } ?: return Result.failure(Exception("File path missing"))
        if (!file.exists()) return Result.failure(Exception("File does not exist on storage"))

        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, file)
            val mime = guessMimeType(file.name)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            // Fallback to wildcard type
            try {
                val authority = "${context.packageName}.fileprovider"
                val contentUri = FileProvider.getUriForFile(context, authority, file)
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                Result.success(Unit)
            } catch (fallbackError: Exception) {
                Result.failure(fallbackError)
            }
        }
    }

    /**
     * Shares a received file to other apps (WhatsApp, Drive, Email, etc.)
     */
    fun shareFile(sharedFile: SharedFile): Result<Unit> {
        val file = sharedFile.filePath?.let { File(it) } ?: return Result.failure(Exception("File path missing"))
        if (!file.exists()) return Result.failure(Exception("File does not exist"))

        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, file)
            val mime = guessMimeType(file.name)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates an output destination for an incoming file in Downloads/Bridge.
     */
    fun createDestinationFile(suggestedName: String): File {
        val sanitizedName = sanitizeFilename(suggestedName)
        val bridgeDir = getBridgeDownloadsDir()
        var destination = File(bridgeDir, sanitizedName)

        // Handle name collision
        if (destination.exists()) {
            val dotIndex = sanitizedName.lastIndexOf('.')
            val baseName = if (dotIndex > 0) sanitizedName.substring(0, dotIndex) else sanitizedName
            val ext = if (dotIndex > 0) sanitizedName.substring(dotIndex) else ""
            var count = 1
            while (destination.exists()) {
                destination = File(bridgeDir, "$baseName ($count)$ext")
                count++
            }
        }
        return destination
    }

    fun getBridgeDownloadsDir(): File {
        // Check if public Downloads directory is directly accessible
        try {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bridge")
            if (publicDir.exists() && publicDir.canWrite()) {
                return publicDir
            }
            if (publicDir.mkdirs() && publicDir.canWrite()) {
                return publicDir
            }
        } catch (ignored: Exception) {}

        // Fallback to app-specific external files dir (guaranteed writable without special permissions on all Android versions)
        val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "Bridge") }
        if (appExternal != null) {
            if (!appExternal.exists()) appExternal.mkdirs()
            if (appExternal.canWrite()) return appExternal
        }

        // Final fallback to internal storage
        val internalDir = File(context.filesDir, "Bridge")
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }

    fun sanitizeFilename(name: String): String {
        val base = File(name).name.replace("..", "_")
        return base.replace("[^a-zA-Z0-9._-]".toRegex(), "_").ifEmpty { "received_${System.currentTimeMillis()}" }
    }
}
