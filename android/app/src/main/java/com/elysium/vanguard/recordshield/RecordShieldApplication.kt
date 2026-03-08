package com.elysium.vanguard.recordshield

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * ============================================================================
 * RecordShieldApplication — Hilt Entry Point
 * ============================================================================
 *
 * Why @HiltAndroidApp: This annotation triggers Hilt's code generation,
 * creating the dependency injection container that lives for the entire
 * lifecycle of the app. All @Inject constructors and @Module providers
 * are resolved from this root component.
 *
 * Why notification channels here: Android 8.0+ requires channels to be
 * created before any notification is posted. The foreground service
 * notification needs this channel to exist BEFORE the service starts.
 * Creating it in Application.onCreate() guarantees it's always ready.
 * ============================================================================
 */
@HiltAndroidApp
class RecordShieldApplication : Application() {

    companion object {
        const val RECORDING_CHANNEL_ID = "recording_service_channel"
        const val UPLOAD_CHANNEL_ID = "upload_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // Recording Channel — High importance for foreground service visibility
        val recordingChannel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            "Recording Service",
            NotificationManager.IMPORTANCE_LOW // Why LOW: Persistent but non-intrusive
        ).apply {
            description = "Shows when evidence recording is active"
            setShowBadge(false)
        }

        // Upload Channel — For background upload progress
        val uploadChannel = NotificationChannel(
            UPLOAD_CHANNEL_ID,
            "Evidence Upload",
            NotificationManager.IMPORTANCE_MIN // Why MIN: Silent background operation
        ).apply {
            description = "Shows evidence upload progress to cloud"
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(recordingChannel)
        manager.createNotificationChannel(uploadChannel)
    }
}
