package com.ankit.blocker.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.ankit.blocker.MainActivity
import com.ankit.blocker.R
import com.ankit.blocker.utils.Logger

/**
 * NotificationHelper provides utility methods for managing notification channels and permissions
 * in the Blocker application. It includes functionalities to register notification channels
 * and build notifications for foreground services.
 */
object NotificationHelper {
    private const val TAG = "Blocker.NotificationHelper"

    // Notification channel IDs
    const val CRITICAL_CHANNEL_ID: String = "blocker.notification.channel.CRITICAL"
    const val SERVICE_CHANNEL_ID: String = "blocker.notification.channel.SERVICE"
    const val ALERTS_CHANNEL_ID: String = "blocker.notification.channel.ALERTS"

    // Notification IDs
    const val FOREGROUND_SERVICE_NOTIFICATION_ID = 1001
    const val CRITICAL_ALERT_NOTIFICATION_ID = 1002
    const val PERMISSION_REQUEST_NOTIFICATION_ID = 1003

    /**
     * Registers notification channels for the application. This method creates and registers
     * channels for critical alerts, service notifications, and general alerts.
     *
     * @param context The application context used to access system services.
     */
    fun registerNotificationChannels(context: Context) {
        try {
            Logger.d(TAG, "Registering notification channels")

                // Create critical channel for important system alerts
                val criticalChannel = NotificationChannel(
                    CRITICAL_CHANNEL_ID,
                    "Critical Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Critical system alerts that require immediate attention to ensure Blocker functions properly."
                    enableVibration(true)
                    setShowBadge(true)
                }

                // Create service channel for foreground service notifications
                // Use LOW importance for non-removable persistent notification
                // IMPORTANT: Android allows removing DEFAULT/HIGH importance notifications even with setOngoing(true)
                // LOW importance notifications are harder to dismiss and don't make sound
                val serviceChannel = NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "Protection Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Ongoing notification that Blocker protection services are running."
                    setShowBadge(false)
                    enableVibration(false)
                    // Make notification visible on lockscreen for security awareness
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    // Prevent user from changing importance (Android 8+)
                    setBypassDnd(false)
                }

                // Create alerts channel for general app alerts
                val alertsChannel = NotificationChannel(
                    ALERTS_CHANNEL_ID,
                    "General Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "General notifications about blocked content and protection status."
                    enableVibration(true)
                    setShowBadge(true)
                }

                // Register channels with system
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannels(
                    listOf(criticalChannel, serviceChannel, alertsChannel)
                )

                Logger.d(TAG, "Successfully registered 3 notification channels")

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to register notification channels", e)
        }
    }

    /**
     * Builds and returns a notification for a foreground service with the specified content.
     *
     * @param context The application context used to access system services.
     * @param title The title of the notification.
     * @param content The content text of the notification.
     * @return A Notification object representing the foreground service notification.
     */
    fun buildForegroundServiceNotification(
        context: Context,
        title: String = "Blocker Protection Active",
        content: String = "Settings protection is running in the background"
    ): Notification {
        return try {
            // Create intent to open main activity when notification is tapped
            val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val tapPendingIntent = PendingIntent.getActivity(
                context,
                0,
                mainActivityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Create delete intent to detect when user dismisses the notification
            val dismissIntent = Intent("com.ankit.blocker.NOTIFICATION_DISMISSED").apply {
                setPackage(context.packageName)
            }
            val deletePendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(tapPendingIntent)
                .setDeleteIntent(deletePendingIntent) // Detects when notification is dismissed
                .setOngoing(true) // Makes notification persistent (non-swipeable on most devices)
                .setAutoCancel(false) // Prevent dismissal on tap
                .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority = harder to dismiss
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true) // Don't re-alert on updates
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lockscreen
                .setShowWhen(false) // Don't show timestamp (cleaner look)

            // For Android 12+ (API 31+), set foreground service behavior
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            }

            builder.build()

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to build foreground service notification", e)

            // Fallback basic notification (must also include delete intent for consistency)
            try {
                val dismissIntent = Intent("com.ankit.blocker.NOTIFICATION_DISMISSED").apply {
                    setPackage(context.packageName)
                }
                val deletePendingIntent = PendingIntent.getBroadcast(
                    context,
                    1001,
                    dismissIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("Blocker Active")
                    .setContentText("Protection running")
                    .setDeleteIntent(deletePendingIntent)
                    .setOngoing(true)
                    .build()
            } catch (fallbackException: Exception) {
                // Ultimate fallback - minimal notification
                NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("Blocker Active")
                    .setOngoing(true)
                    .build()
            }
        }
    }

    /**
     * Builds a critical alert notification for important system messages.
     *
     * @param context The application context.
     * @param title The notification title.
     * @param content The notification content.
     * @param actionIntent Optional intent for notification action.
     * @return A Notification object for critical alerts.
     */
    fun buildCriticalAlertNotification(
        context: Context,
        title: String,
        content: String,
        actionIntent: Intent? = null
    ): Notification {
        val builder = NotificationCompat.Builder(context, CRITICAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)

        actionIntent?.let { intent ->
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pendingIntent)
        }

        return builder.build()
    }

    /**
     * Shows a notification prompting the user to enable required permissions.
     *
     * @param context The application context.
     * @param permissionType The type of permission needed.
     */
    fun showPermissionRequestNotification(
        context: Context,
        permissionType: String = "accessibility"
    ) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val title = "Permission Required"
            val content = when (permissionType.lowercase()) {
                "accessibility" -> "Accessibility permission is required for Blocker to function properly"
                "overlay" -> "Display over other apps permission is required for blocking to work"
                "device_admin" -> "Device administrator permission is required for enhanced protection"
                else -> "Additional permission is required for Blocker to function properly"
            }

            val settingsIntent = when (permissionType.lowercase()) {
                "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = "package:${context.packageName}".toUri()
                }
                else -> Intent(context, MainActivity::class.java)
            }

            val notification = buildCriticalAlertNotification(context, title, content, settingsIntent)
            notificationManager.notify(PERMISSION_REQUEST_NOTIFICATION_ID, notification)

            Logger.d(TAG, "Showed permission request notification for: $permissionType")

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to show permission request notification", e)
        }
    }

    /**
     * Shows a high-priority notification after an app update if critical permissions are missing.
     * This acts as a "Self-Healing" mechanism prompt.
     *
     * @param context The application context.
     */
    fun showPostUpdatePermissionRecoveryNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val title = "Update Complete: Action Required"
            val content = "Tap here to restore protection settings that may have been disabled during the update."

            // Intent to open MainActivity which should handle checking/prompting permissions
            val actionIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val notification = buildCriticalAlertNotification(context, title, content, actionIntent)
            
            // Use a specific ID for this recovery notification
            notificationManager.notify(PERMISSION_REQUEST_NOTIFICATION_ID, notification)

            Logger.d(TAG, "Showed post-update permission recovery notification")

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to show post-update recovery notification", e)
        }
    }

    /**
     * Cancels a notification by its ID.
     *
     * @param context The application context.
     * @param notificationId The ID of the notification to cancel.
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)
            Logger.d(TAG, "Cancelled notification with ID: $notificationId")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to cancel notification", e)
        }
    }
}