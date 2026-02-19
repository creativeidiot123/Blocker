package com.ankit.blocker.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.blocker.services.BlockerForegroundService
import com.ankit.blocker.utils.Logger
import com.ankit.blocker.utils.PreferencesHelper

/**
 * BroadcastReceiver that handles notification dismissal events.
 *
 * If user dismisses the foreground service notification, this receiver
 * will immediately restart the service to recreate the notification,
 * making it effectively non-removable.
 *
 * Battery Optimization:
 * - Uses throttling (5-second minimum between restarts) to prevent rapid restart loops
 * - Only active when protection is enabled
 * - Lightweight operation: just starts service and exits (no background work)
 * - BroadcastReceiver automatically terminates after onReceive() completes
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Blocker.NotificationDismissReceiver"
        const val ACTION_NOTIFICATION_DISMISSED = "com.ankit.blocker.NOTIFICATION_DISMISSED"

        // Throttling to prevent restart loops (battery optimization)
        private const val RESTART_THROTTLE_MS = 5000L // 5 seconds
        private const val PREF_LAST_RESTART = "notification_dismiss_last_restart"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_NOTIFICATION_DISMISSED -> {
                Logger.w(TAG, "Foreground service notification was dismissed by user")

                // Check if protection is still enabled (fast check)
                val isProtectionEnabled = PreferencesHelper.isProtectionEnabled(context)

                if (!isProtectionEnabled) {
                    Logger.d(TAG, "Protection is disabled - notification dismissal is acceptable")
                    return
                }

                // Throttling: Prevent rapid restart loops to save battery
                val currentTime = System.currentTimeMillis()
                val prefs = context.getSharedPreferences("blocker_receiver", Context.MODE_PRIVATE)
                val lastRestartTime = prefs.getLong(PREF_LAST_RESTART, 0L)

                if (currentTime - lastRestartTime < RESTART_THROTTLE_MS) {
                    val remainingThrottle = RESTART_THROTTLE_MS - (currentTime - lastRestartTime)
                    Logger.d(TAG, "Throttled - skipping restart (${remainingThrottle}ms remaining)")
                    return
                }

                // Update last restart time
                prefs.edit().putLong(PREF_LAST_RESTART, currentTime).apply()

                Logger.d(TAG, "Protection is enabled - recreating notification")

                // Recreate notification (lightweight operation)
                val isServiceRunning = BlockerForegroundService.isServiceRunning(context)
                try {
                    if (isServiceRunning) {
                        // Service is running - just recreate notification without restarting monitoring
                        Logger.d(TAG, "Service is running - recreating notification only")
                        BlockerForegroundService.recreateNotification(context)
                    } else {
                        // Service not running - start it fully
                        Logger.d(TAG, "Service not running - starting it")
                        BlockerForegroundService.startService(context)
                    }
                    Logger.d(TAG, "Notification recreation requested after dismissal")
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to recreate notification after dismissal", e)
                }

                // BroadcastReceiver automatically terminates here - no background work
            }
        }
    }
}
