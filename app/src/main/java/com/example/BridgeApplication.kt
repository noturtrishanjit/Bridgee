package com.example

import android.app.Application
import com.example.server.BridgeHttpServer
import com.example.transfer.BridgeClient
import com.example.transfer.StorageManager
import com.google.firebase.FirebaseApp

class BridgeApplication : Application() {

    lateinit var storageManager: StorageManager
        private set

    lateinit var bridgeClient: BridgeClient
        private set

    var httpServer: BridgeHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        storageManager = StorageManager(this)
        bridgeClient = BridgeClient(this, storageManager)
    }

    companion object {
        lateinit var instance: BridgeApplication
            private set
    }
}
