package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BridgeApplication
import com.example.model.DeviceProfile
import com.example.model.SharedFile
import com.example.model.SharingSession
import com.example.model.TransferItem
import com.example.model.TransferStatus
import com.example.server.BridgeHttpServer
import com.example.server.NetworkUtils
import com.example.service.TransferForegroundService
import com.example.transfer.RemotePeerSession
import com.example.transfer.TransferManager
import com.example.ui.theme.ThemeMode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BridgeApplication
    private val storageManager = app.storageManager
    private val bridgeClient = app.bridgeClient
    private val prefs = application.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)

    // Device Profile & Identity
    private val defaultDeviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    private val _deviceProfile = MutableStateFlow(
        DeviceProfile(
            deviceName = prefs.getString("device_custom_name", defaultDeviceName) ?: defaultDeviceName,
            avatarId = prefs.getString("device_avatar_id", "cyber_falcon") ?: "cyber_falcon",
            customHostUrl = prefs.getString("custom_host_url", "") ?: "",
            useCustomHostUrl = prefs.getBoolean("use_custom_host_url", false),
            isEncryptionEnabled = prefs.getBoolean("is_encryption_enabled", true),
            customPassphrase = prefs.getString("custom_passphrase", "") ?: ""
        )
    )
    val deviceProfile: StateFlow<DeviceProfile> = _deviceProfile.asStateFlow()

    fun setDeviceName(name: String) {
        val trimmed = name.trim().take(30)
        _deviceProfile.value = _deviceProfile.value.copy(deviceName = trimmed)
        prefs.edit().putString("device_custom_name", trimmed).apply()
        app.httpServer?.deviceName = trimmed
        _sharingSession.value = _sharingSession.value?.copy(deviceName = trimmed)
    }

    fun setAvatarId(avatarId: String) {
        _deviceProfile.value = _deviceProfile.value.copy(avatarId = avatarId)
        prefs.edit().putString("device_avatar_id", avatarId).apply()
        app.httpServer?.avatarId = avatarId
        _sharingSession.value = _sharingSession.value?.copy(avatarId = avatarId)
    }

    fun setCustomHostUrl(url: String) {
        val trimmed = url.trim()
        _deviceProfile.value = _deviceProfile.value.copy(customHostUrl = trimmed)
        prefs.edit().putString("custom_host_url", trimmed).apply()
        _sharingSession.value = _sharingSession.value?.copy(customHostUrl = trimmed)
    }

    fun setUseCustomHostUrl(enabled: Boolean) {
        _deviceProfile.value = _deviceProfile.value.copy(useCustomHostUrl = enabled)
        prefs.edit().putBoolean("use_custom_host_url", enabled).apply()
        _sharingSession.value = _sharingSession.value?.copy(useCustomHostUrl = enabled)
    }

    fun setEncryptionEnabled(enabled: Boolean) {
        _deviceProfile.value = _deviceProfile.value.copy(isEncryptionEnabled = enabled)
        prefs.edit().putBoolean("is_encryption_enabled", enabled).apply()
        app.httpServer?.isEncryptionEnabled = enabled
        _sharingSession.value = _sharingSession.value?.copy(isEncryptionEnabled = enabled)
    }

    fun setCustomPassphrase(passphrase: String) {
        _deviceProfile.value = _deviceProfile.value.copy(customPassphrase = passphrase)
        prefs.edit().putString("custom_passphrase", passphrase).apply()
        app.httpServer?.encryptionPassphrase = passphrase
    }

    private val _themeMode = MutableStateFlow(
        try {
            val saved = prefs.getString("theme_mode", ThemeMode.LIGHT.name) ?: ThemeMode.LIGHT.name
            ThemeMode.valueOf(saved)
        } catch (e: Exception) {
            ThemeMode.LIGHT
        }
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    private val _selectedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val selectedFiles: StateFlow<List<SharedFile>> = _selectedFiles.asStateFlow()

    private val _sharingSession = MutableStateFlow<SharingSession?>(null)
    val sharingSession: StateFlow<SharingSession?> = _sharingSession.asStateFlow()

    private val _networkInfo = MutableStateFlow(NetworkUtils.getLocalIpAddress(application))
    val networkInfo: StateFlow<NetworkUtils.NetworkInfo> = _networkInfo.asStateFlow()

    private val _availableIps = MutableStateFlow<List<NetworkUtils.NetworkInfo>>(emptyList())
    val availableIps: StateFlow<List<NetworkUtils.NetworkInfo>> = _availableIps.asStateFlow()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    // Receiver state
    private val _receiverUrl = MutableStateFlow("")
    val receiverUrl: StateFlow<String> = _receiverUrl.asStateFlow()

    private val _receiverFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val receiverFiles: StateFlow<List<SharedFile>> = _receiverFiles.asStateFlow()

    private val _remoteSessionInfo = MutableStateFlow<RemotePeerSession?>(null)
    val remoteSessionInfo: StateFlow<RemotePeerSession?> = _remoteSessionInfo.asStateFlow()

    private val _selectedRemoteFileIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedRemoteFileIds: StateFlow<Set<String>> = _selectedRemoteFileIds.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _receiverMessage = MutableStateFlow<String?>(null)
    val receiverMessage: StateFlow<String?> = _receiverMessage.asStateFlow()

    // Transfers
    val allTransfers = TransferManager.transfers

    val activeTransfers: StateFlow<List<TransferItem>> = allTransfers.combine(_selectedFiles) { list, _ ->
        list.filter { it.status == TransferStatus.TRANSFERRING || it.status == TransferStatus.CONNECTING || it.status == TransferStatus.WAITING || it.status == TransferStatus.PAUSED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedTransfers: StateFlow<List<TransferItem>> = allTransfers.combine(_selectedFiles) { list, _ ->
        list.filter { it.status == TransferStatus.COMPLETED || it.status == TransferStatus.FAILED || it.status == TransferStatus.CANCELLED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _serverSelfTestStatus = MutableStateFlow<String?>(null)
    val serverSelfTestStatus: StateFlow<String?> = _serverSelfTestStatus.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val receivedFiles: StateFlow<List<SharedFile>> = _receivedFiles.asStateFlow()

    private val _lastReceivedNotification = MutableStateFlow<String?>(null)
    val lastReceivedNotification: StateFlow<String?> = _lastReceivedNotification.asStateFlow()

    private val firebaseAuth: FirebaseAuth? by lazy {
        try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    private val _currentUser = MutableStateFlow<FirebaseUser?>(firebaseAuth?.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    init {
        try {
            firebaseAuth?.addAuthStateListener { auth ->
                _currentUser.value = auth.currentUser
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        refreshNetworkInfo()
        loadReceivedFiles()
    }

    suspend fun signInWithGoogleCredential(idToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val auth = firebaseAuth ?: return@withContext Result.failure(Exception("FirebaseAuth not initialized"))
        try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        try {
            firebaseAuth?.signOut()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _currentUser.value = null
    }

    fun setTab(index: Int) {
        _currentTab.value = index
    }

    fun refreshNetworkInfo() {
        val all = NetworkUtils.getAllAvailableIps(getApplication())
        _availableIps.value = all
        val best = NetworkUtils.getLocalIpAddress(getApplication())
        _networkInfo.value = best
    }

    fun selectNetworkIp(info: NetworkUtils.NetworkInfo) {
        _networkInfo.value = info
        _sharingSession.value?.let { current ->
            _sharingSession.value = current.copy(hostIp = info.ipAddress)
        }
    }

    fun addFiles(uris: List<Uri>) {
        val newFiles = uris.mapNotNull { storageManager.resolveSharedFile(it) }
        val updated = _selectedFiles.value + newFiles
        _selectedFiles.value = updated
        app.httpServer?.setFiles(updated)
        // Ensure server is active if it was previously stopped
        if (_sharingSession.value == null || app.httpServer?.isServerActive() != true) {
            startSharing()
        }
    }

    fun removeFile(fileId: String) {
        val updated = _selectedFiles.value.filterNot { it.id == fileId }
        _selectedFiles.value = updated
        app.httpServer?.setFiles(updated)
    }

    fun clearSelectedFiles() {
        _selectedFiles.value = emptyList()
        app.httpServer?.setFiles(emptyList())
    }

    fun addSampleTestFile() {
        val cacheDir = getApplication<BridgeApplication>().cacheDir
        val sampleFile = File(cacheDir, "Bridge_Quick_Test.txt")
        sampleFile.writeText("Bridge Local File Sharing Test\n\nFile transferred directly over local network peer-to-peer.\nTimestamp: ${Date()}\nStatus: Direct Streaming Active.\nSecurity: Authenticated AES-256-GCM.")
        val newFile = SharedFile(
            id = UUID.randomUUID().toString(),
            name = sampleFile.name,
            size = sampleFile.length(),
            mimeType = "text/plain",
            filePath = sampleFile.absolutePath,
            isUploadedByPeer = false
        )
        val updated = _selectedFiles.value + newFile
        _selectedFiles.value = updated
        app.httpServer?.setFiles(updated)
        if (_sharingSession.value == null || app.httpServer?.isServerActive() != true) {
            startSharing()
        }
    }

    fun testServerConnection() {
        val session = _sharingSession.value ?: run {
            _serverSelfTestStatus.value = "Server is not started"
            return
        }
        _serverSelfTestStatus.value = "Testing local server ping..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("http://127.0.0.1:${session.port}/api/session")
                    .build()
                val response = client.newCall(request).execute()
                val code = response.code
                response.close()
                if (code in 200..299) {
                    _serverSelfTestStatus.value = "Active & Responding (HTTP $code OK — Server Ready!)"
                } else {
                    _serverSelfTestStatus.value = "HTTP response $code from local server"
                }
            } catch (e: Exception) {
                _serverSelfTestStatus.value = "Self-test ping note: ${e.message ?: "Waiting for network"}"
            }
        }
    }

    fun startSharing(preferredPort: Int = 8080) {
        if (_sharingSession.value != null && app.httpServer?.isServerActive() == true) {
            testServerConnection()
            return
        }

        refreshNetworkInfo()
        val net = _networkInfo.value
        val token = UUID.randomUUID().toString().take(8)
        val profile = _deviceProfile.value

        val server = BridgeHttpServer(
            context = getApplication(),
            storageManager = storageManager,
            onFilesChanged = {
                app.httpServer?.let { s ->
                    _selectedFiles.value = s.getFiles()
                }
            },
            onPeersChanged = { count ->
                _sharingSession.value?.let { current ->
                    _sharingSession.value = current.copy(connectedClientsCount = count)
                }
            },
            onFileReceived = { newFile ->
                viewModelScope.launch {
                    val updated = listOf(newFile) + _receivedFiles.value.filterNot { it.filePath == newFile.filePath }
                    _receivedFiles.value = updated
                    _lastReceivedNotification.value = "Received: ${newFile.name}"
                }
            },
            onError = { err ->
                _serverError.value = "Server error: ${err.localizedMessage ?: "Network interrupted"}"
            }
        )

        server.deviceName = profile.deviceName
        server.avatarId = profile.avatarId
        server.isEncryptionEnabled = profile.isEncryptionEnabled
        server.encryptionPassphrase = profile.customPassphrase

        app.httpServer = server
        server.setFiles(_selectedFiles.value)
        val startResult = server.start(preferredPort, token)

        startResult.onSuccess { boundPort ->
            _serverError.value = null
            val session = SharingSession(
                sessionId = token,
                token = token,
                hostIp = net.ipAddress,
                port = boundPort,
                isActive = true,
                deviceName = profile.deviceName,
                avatarId = profile.avatarId,
                customHostUrl = profile.customHostUrl,
                useCustomHostUrl = profile.useCustomHostUrl,
                isEncryptionEnabled = profile.isEncryptionEnabled
            )
            _sharingSession.value = session
            TransferForegroundService.start(getApplication(), session.connectionUrl)
            testServerConnection()
        }.onFailure { err ->
            _serverError.value = "Could not start server on port $preferredPort: ${err.localizedMessage ?: "Port in use"}"
            app.httpServer = null
            _sharingSession.value = null
        }
    }

    fun restartServer(preferredPort: Int = 8080) {
        stopSharing()
        startSharing(preferredPort)
    }

    fun stopSharing() {
        app.httpServer?.stop()
        app.httpServer = null
        _sharingSession.value = null
        _serverError.value = null
        _serverSelfTestStatus.value = null
        TransferForegroundService.stop(getApplication())
    }

    fun setReceiverUrl(url: String) {
        _receiverUrl.value = url
    }

    fun connectToSender(inputUrl: String) {
        val trimmed = inputUrl.trim()
        if (trimmed.isEmpty()) return

        val formattedUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else {
            trimmed
        }

        _receiverUrl.value = formattedUrl
        _isConnecting.value = true
        _receiverMessage.value = null

        viewModelScope.launch {
            val uri = Uri.parse(formattedUrl)
            val token = uri.getQueryParameter("token") ?: ""
            val baseUrl = "http://${uri.host}:${if (uri.port != -1) uri.port else 8080}"

            // Fetch session info to get sender's avatar, device name, and encryption status
            val sessionResult = bridgeClient.fetchSessionInfo(baseUrl, token)
            sessionResult.onSuccess {
                _remoteSessionInfo.value = it
            }

            val result = bridgeClient.fetchRemoteFiles(baseUrl, token)
            _isConnecting.value = false

            result.onSuccess { files ->
                _receiverFiles.value = files
                _selectedRemoteFileIds.value = files.map { it.id }.toSet()
                val peerName = _remoteSessionInfo.value?.deviceName ?: "Host"
                _receiverMessage.value = if (files.isEmpty()) "Connected to $peerName! No files shared yet." else "Connected to $peerName: ${files.size} available files"
            }.onFailure { error ->
                _receiverMessage.value = "Connection failed: ${error.localizedMessage ?: "Device unreachable"}"
            }
        }
    }

    fun toggleRemoteFileSelection(fileId: String) {
        val current = _selectedRemoteFileIds.value.toMutableSet()
        if (current.contains(fileId)) {
            current.remove(fileId)
        } else {
            current.add(fileId)
        }
        _selectedRemoteFileIds.value = current
    }

    fun selectAllRemoteFiles(select: Boolean) {
        if (select) {
            _selectedRemoteFileIds.value = _receiverFiles.value.map { it.id }.toSet()
        } else {
            _selectedRemoteFileIds.value = emptySet()
        }
    }

    fun downloadSelectedRemoteFiles() {
        val filesToDownload = _receiverFiles.value.filter { _selectedRemoteFileIds.value.contains(it.id) }
        if (filesToDownload.isEmpty()) return

        val url = _receiverUrl.value
        val uri = Uri.parse(url)
        val token = uri.getQueryParameter("token") ?: ""
        val baseUrl = "http://${uri.host}:${if (uri.port != -1) uri.port else 8080}"
        val passphrase = _deviceProfile.value.customPassphrase

        // Switch to Transfers tab so user sees real-time progress
        _currentTab.value = 2

        viewModelScope.launch {
            for (file in filesToDownload) {
                bridgeClient.downloadFile(baseUrl, token, file, passphrase)
            }
            loadReceivedFiles()
        }
    }

    fun cancelTransfer(id: String) {
        TransferManager.cancelTransfer(id)
    }

    fun pauseTransfer(id: String) {
        TransferManager.pauseTransfer(id)
    }

    fun resumeTransfer(id: String) {
        TransferManager.resumeTransfer(id)
    }

    fun clearCompletedTransfers() {
        TransferManager.clearCompleted()
    }

    fun loadReceivedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = storageManager.getReceivedFiles()
            _receivedFiles.value = list
        }
    }

    fun openFile(file: SharedFile): Result<Unit> {
        return storageManager.openFile(file)
    }

    fun shareFile(file: SharedFile): Result<Unit> {
        return storageManager.shareFile(file)
    }

    fun dismissNotification() {
        _lastReceivedNotification.value = null
    }
}
