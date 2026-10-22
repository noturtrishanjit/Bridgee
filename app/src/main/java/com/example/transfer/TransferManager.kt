package com.example.transfer

import com.example.model.TransferDirection
import com.example.model.TransferItem
import com.example.model.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

object TransferManager {

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val cancellationTokens = ConcurrentHashMap<String, Boolean>()
    private val pauseTokens = ConcurrentHashMap<String, Boolean>()

    // Speed tracking helper: transferId -> Pair(lastTimestamp, lastBytes)
    private val speedMap = ConcurrentHashMap<String, Pair<Long, Long>>()

    fun startTransfer(
        id: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        direction: TransferDirection,
        peerAddress: String = "",
        peerDeviceName: String = "",
        isEncrypted: Boolean = true
    ): TransferItem {
        cancellationTokens[id] = false
        pauseTokens[id] = false
        speedMap[id] = Pair(System.currentTimeMillis(), 0L)

        val item = TransferItem(
            id = id,
            fileId = fileId,
            fileName = fileName,
            totalBytes = totalBytes,
            transferredBytes = 0L,
            direction = direction,
            status = TransferStatus.TRANSFERRING,
            peerAddress = peerAddress,
            peerDeviceName = peerDeviceName,
            isEncrypted = isEncrypted
        )

        val current = _transfers.value.toMutableList()
        current.removeAll { it.id == id }
        current.add(0, item)
        _transfers.value = current
        return item
    }

    fun updateProgress(id: String, transferredBytes: Long) {
        val now = System.currentTimeMillis()
        val lastData = speedMap[id] ?: Pair(now, 0L)
        val timeDeltaMs = now - lastData.first
        var speed = 0L

        if (timeDeltaMs >= 500) {
            val bytesDelta = transferredBytes - lastData.second
            speed = if (timeDeltaMs > 0) (bytesDelta * 1000L) / timeDeltaMs else 0L
            speedMap[id] = Pair(now, transferredBytes)
        }

        val current = _transfers.value.map { item ->
            if (item.id == id) {
                val effectiveSpeed = if (speed > 0) speed else item.speedBytesPerSec
                item.copy(
                    transferredBytes = transferredBytes,
                    speedBytesPerSec = effectiveSpeed,
                    status = TransferStatus.TRANSFERRING
                )
            } else {
                item
            }
        }
        _transfers.value = current
    }

    fun completeTransfer(id: String) {
        cancellationTokens.remove(id)
        pauseTokens.remove(id)
        speedMap.remove(id)

        val current = _transfers.value.map { item ->
            if (item.id == id) {
                item.copy(
                    transferredBytes = item.totalBytes,
                    status = TransferStatus.COMPLETED,
                    speedBytesPerSec = 0L
                )
            } else {
                item
            }
        }
        _transfers.value = current
    }

    fun failTransfer(id: String, error: String) {
        cancellationTokens.remove(id)
        pauseTokens.remove(id)
        speedMap.remove(id)

        val current = _transfers.value.map { item ->
            if (item.id == id) {
                item.copy(
                    status = TransferStatus.FAILED,
                    speedBytesPerSec = 0L,
                    errorMessage = error
                )
            } else {
                item
            }
        }
        _transfers.value = current
    }

    fun cancelTransfer(id: String) {
        cancellationTokens[id] = true
        speedMap.remove(id)

        val current = _transfers.value.map { item ->
            if (item.id == id) {
                item.copy(
                    status = TransferStatus.CANCELLED,
                    speedBytesPerSec = 0L
                )
            } else {
                item
            }
        }
        _transfers.value = current
    }

    fun pauseTransfer(id: String) {
        pauseTokens[id] = true
        val current = _transfers.value.map { item ->
            if (item.id == id) {
                item.copy(
                    status = TransferStatus.PAUSED,
                    speedBytesPerSec = 0L
                )
            } else {
                item
            }
        }
        _transfers.value = current
    }

    fun resumeTransfer(id: String) {
        pauseTokens[id] = false
        val current = _transfers.value.map { item ->
            if (item.id == id) {
                item.copy(
                    status = TransferStatus.TRANSFERRING
                )
            } else {
                item
            }
        }
        _transfers.value = current
    }

    fun isCancelled(id: String): Boolean = cancellationTokens[id] == true
    fun isPaused(id: String): Boolean = pauseTokens[id] == true

    fun clearCompleted() {
        val current = _transfers.value.filter {
            it.status == TransferStatus.TRANSFERRING || it.status == TransferStatus.CONNECTING || it.status == TransferStatus.WAITING
        }
        _transfers.value = current
    }
}
