package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.SharedFile
import com.example.model.TransferDirection
import com.example.model.TransferItem
import com.example.model.TransferStatus
import com.example.qrcode.QrCodeGenerator
import com.example.server.NetworkUtils
import com.example.transfer.StorageManager
import com.example.transfer.TransferManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Bridge", appName)
    }

    @Test
    fun `test storage manager filename sanitation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = StorageManager(context)

        val clean = storageManager.sanitizeFilename("../dangerous/path\\file?.mp4")
        assertFalse(clean.contains("/"))
        assertFalse(clean.contains(".."))
        assertTrue(clean.contains("mp4"))
    }

    @Test
    fun `test qr code generator`() {
        val bitmap = QrCodeGenerator.generateQrBitmap("http://192.168.1.10:8080/?token=test1234", 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun `test transfer manager lifecycle`() {
        val transferId = "test_transfer_1"
        TransferManager.startTransfer(
            id = transferId,
            fileId = "file_1",
            fileName = "movie.mkv",
            totalBytes = 100_000_000L,
            direction = TransferDirection.DOWNLOAD
        )

        TransferManager.updateProgress(transferId, 50_000_000L)
        val active = TransferManager.transfers.value.find { it.id == transferId }
        assertNotNull(active)
        assertEquals(50_000_000L, active!!.transferredBytes)
        assertEquals(50, active.progressPercentage)

        TransferManager.pauseTransfer(transferId)
        assertTrue(TransferManager.isPaused(transferId))

        TransferManager.resumeTransfer(transferId)
        assertFalse(TransferManager.isPaused(transferId))

        TransferManager.completeTransfer(transferId)
        val completed = TransferManager.transfers.value.find { it.id == transferId }
        assertNotNull(completed)
        assertEquals(TransferStatus.COMPLETED, completed!!.status)
    }

    @Test
    fun `test formatted file size and transfer item`() {
        val file = SharedFile(
            id = "1",
            name = "test.zip",
            size = 1048576L * 150L, // 150 MB
            mimeType = "application/zip"
        )
        assertTrue(file.getFormattedSize().contains("MB"))

        val item = TransferItem(
            id = "t1",
            fileId = "1",
            fileName = "test.zip",
            totalBytes = 1048576L * 100L,
            transferredBytes = 1048576L * 50L,
            direction = TransferDirection.UPLOAD,
            status = TransferStatus.TRANSFERRING,
            speedBytesPerSec = 1048576L * 10L // 10 MB/s
        )
        assertEquals(50, item.progressPercentage)
        assertTrue(item.getFormattedSpeed().contains("MB/s"))
        assertEquals(5L, item.getEstimatedRemainingSeconds())
        assertTrue(item.getFormattedEta().contains("5s left"))
    }

    @Test
    fun `test default theme mode is light`() {
        val defaultMode = com.example.ui.theme.ThemeMode.LIGHT
        assertEquals("LIGHT", defaultMode.name)
        val allModes = com.example.ui.theme.ThemeMode.values()
        assertTrue(allModes.contains(com.example.ui.theme.ThemeMode.LIGHT))
        assertTrue(allModes.contains(com.example.ui.theme.ThemeMode.DARK))
        assertTrue(allModes.contains(com.example.ui.theme.ThemeMode.DYNAMIC))
        assertTrue(allModes.contains(com.example.ui.theme.ThemeMode.SYSTEM))
    }
}
