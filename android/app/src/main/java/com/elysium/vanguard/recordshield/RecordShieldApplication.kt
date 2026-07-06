package com.elysium.vanguard.recordshield

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import javax.inject.Inject

@HiltAndroidApp
class RecordShieldApplication : Application() {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    companion object {
        const val RECORDING_CHANNEL_ID = "stealth_recording_channel"
        const val UPLOAD_CHANNEL_ID = "stealth_upload_channel"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager with HiltWorkerFactory
        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        )

        // Create stealth notification channels (IMPORTANCE_MIN, no sound/vibration)
        com.elysium.vanguard.recordshield.service.StealthNotificationManager
            .createStealthChannel(this)

        // Init file-based logger (Honor LOGLIMIT kills logcat)
        com.elysium.vanguard.recordshield.util.LogFile.init(this)
    }
}
