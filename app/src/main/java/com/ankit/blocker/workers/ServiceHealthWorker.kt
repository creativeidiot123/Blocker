package com.ankit.blocker.workers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ankit.blocker.generics.ServiceBinder
import com.ankit.blocker.helpers.NotificationHelper
import com.ankit.blocker.utils.Logger
import com.ankit.blocker.utils.PreferencesHelper
import com.ankit.blocker.utils.Utils
import com.ankit.blocker.MainActivity

/**
 * Periodic worker that checks foreground service health every hour.
 * Ensures the foreground service remains active when protection is enabled.
 *
 * This provides an additional layer of reliability beyond the service's
 * internal 30-second monitoring, catching cases where the service dies
 * silently or is killed by the system.
 */
class ServiceHealthWorker(
    private val context: Context,
    private val workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "Blocker.ServiceHealthWorker"
        const val UNIQUE_WORK_NAME = "BlockerServiceHealthCheck"

        // Preferences keys for tracking restart attempts
        private const val PREF_RESTART_COUNT = "service_restart_count"
        private const val PREF_LAST_RESTART_TIME = "service_last_restart_time"
        private const val PREF_RESTART_RESET_TIME = "service_restart_reset_time"

        // Restart attempt limits
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val RESTART_RESET_INTERVAL_MS = 3600000L // 1 hour

        // Notification throttling
        private const val NOTIFICATION_COOLDOWN_MS = 300000L // 5 minutes
        private const val PREF_LAST_NOTIFICATION_TIME = "service_last_notification_time"

        // Accessibility notification has separate, longer cooldown for hourly reminders
        private const val ACCESSIBILITY_NOTIFICATION_COOLDOWN_MS = 3600000L // 1 hour
        private const val PREF_LAST_ACCESSIBILITY_NOTIFICATION_TIME = "last_accessibility_notification_time"

        // Permanent failure flag
        private const val PREF_PERMANENT_FAILURE = "service_permanent_failure"
    }

    // Cache SharedPreferences to avoid repeated object creation
    private val healthPrefs by lazy {
        context.getSharedPreferences("blocker_health", Context.MODE_PRIVATE)
    }

    override fun doWork(): Result {
        Logger.d(TAG, "ServiceHealthWorker running hourly health check")

        return try {
            val isProtectionEnabled = PreferencesHelper.isProtectionEnabled(context)

            if (!isProtectionEnabled) {
                Logger.d(TAG, "Protection disabled - skipping health check")
                resetRestartTracking()
                return Result.success()
            }

            // Check foreground service using efficient static flag
            val isForegroundServiceRunning = com.ankit.blocker.services.BlockerForegroundService.isServiceRunning(context)

            if (isForegroundServiceRunning) {
                Logger.d(TAG, "Foreground service is healthy and running")
                resetRestartTracking()

                // Clear permanent failure flag if service recovered
                if (healthPrefs.getBoolean(PREF_PERMANENT_FAILURE, false)) {
                    healthPrefs.edit().putBoolean(PREF_PERMANENT_FAILURE, false).apply()
                    Logger.d(TAG, "Cleared permanent failure flag - service recovered")
                }

                // Accessibility service liveness check — catches "zombie" state where
                // the service is enabled in system but not processing events
                val isAccessibilityAlive = com.ankit.blocker.services.BlockerAccessibilityService.isServiceAlive()
                val isAccessibilityEnabled = Utils.isServiceRunning(context, "com.ankit.blocker.services.BlockerAccessibilityService")

                if (!isAccessibilityEnabled) {
                    Logger.w(TAG, "Accessibility service permission REVOKED - protection disabled")
                    showAccessibilityPermissionLostNotification()
                } else if (!isAccessibilityAlive) {
                    Logger.w(TAG, "Accessibility service appears unresponsive - last event too old")
                    showAccessibilityStaleNotification()
                } else {
                    Logger.d(TAG, "Accessibility service is alive and processing events")
                }

                return Result.success()
            }

            // Service is not running — attempt recovery
            Logger.w(TAG, "Foreground service is NOT running - attempting recovery")
            attemptServiceRecovery()

            Result.success()

        } catch (e: Exception) {
            Logger.e(TAG, "Error during health check", e)
            Result.failure()
        }
    }

    // ─── Notification helpers ──────────────────────────────────────────────────

    /** Shows notification when accessibility permission has been revoked (1-hour cooldown). */
    private fun showAccessibilityPermissionLostNotification() {
        postThrottledNotification(
            notificationId = NotificationHelper.CRITICAL_ALERT_NOTIFICATION_ID + 300,
            cooldownMs = ACCESSIBILITY_NOTIFICATION_COOLDOWN_MS,
            cooldownPrefKey = PREF_LAST_ACCESSIBILITY_NOTIFICATION_TIME,
            title = "⚠️ Accessibility Service Disabled",
            content = "Your protection is OFF. Your device may have disabled it. Tap to re-enable now.",
            withMainActivityIntent = true
        )
    }

    /** Shows notification when accessibility service is enabled but appears stale (5-min cooldown). */
    private fun showAccessibilityStaleNotification() {
        postThrottledNotification(
            notificationId = NotificationHelper.CRITICAL_ALERT_NOTIFICATION_ID + 301,
            cooldownMs = NOTIFICATION_COOLDOWN_MS,
            cooldownPrefKey = PREF_LAST_NOTIFICATION_TIME,
            title = "Protection May Be Inactive",
            content = "Settings protection hasn't processed events recently. Tap to check status.",
            withMainActivityIntent = true
        )
    }

    /** Shows notification after a successful service auto-restart (5-min cooldown). */
    private fun showServiceRecoveryNotification(attemptNumber: Int) {
        postThrottledNotification(
            notificationId = NotificationHelper.CRITICAL_ALERT_NOTIFICATION_ID + 100,
            cooldownMs = NOTIFICATION_COOLDOWN_MS,
            cooldownPrefKey = PREF_LAST_NOTIFICATION_TIME,
            title = "Protection Service Recovered",
            content = "Blocker protection service was restarted automatically (attempt $attemptNumber)",
            withMainActivityIntent = false
        )
    }

    /** Shows critical notification when service has permanently failed to restart (no cooldown). */
    private fun showCriticalServiceFailureNotification() {
        postThrottledNotification(
            notificationId = NotificationHelper.CRITICAL_ALERT_NOTIFICATION_ID + 200,
            cooldownMs = 0L, // Always show — permanent-failure alert
            cooldownPrefKey = null,
            title = "Protection Service Failed",
            content = "Blocker protection service has failed to start multiple times. Please open the app to restore protection.",
            withMainActivityIntent = true
        )
    }

    /**
     * Central helper: builds and posts a critical-channel notification.
     *
     * @param notificationId         Unique notification ID.
     * @param cooldownMs             Minimum ms between consecutive posts. Use 0 to always post.
     * @param cooldownPrefKey        SharedPreferences key for last-post timestamp. Null = no throttle.
     * @param title                  Notification title.
     * @param content                Notification body.
     * @param withMainActivityIntent Whether to attach a tap-to-open-MainActivity intent.
     */
    private fun postThrottledNotification(
        notificationId: Int,
        cooldownMs: Long,
        cooldownPrefKey: String?,
        title: String,
        content: String,
        withMainActivityIntent: Boolean
    ) {
        try {
            if (cooldownPrefKey != null && cooldownMs > 0L) {
                val currentTime = System.currentTimeMillis()
                val lastTime = healthPrefs.getLong(cooldownPrefKey, 0L)
                if (currentTime - lastTime < cooldownMs) {
                    Logger.d(TAG, "Skipping notification ($title) – cooldown active")
                    return
                }
                healthPrefs.edit().putLong(cooldownPrefKey, currentTime).apply()
            }

            val actionIntent = if (withMainActivityIntent) Intent(context, MainActivity::class.java) else null
            val notification = NotificationHelper.buildCriticalAlertNotification(context, title, content, actionIntent)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(notificationId, notification)

            Logger.d(TAG, "Posted notification: $title")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to post notification: $title", e)
        }
    }

    // ─── Service recovery ──────────────────────────────────────────────────────

    /**
     * Attempts to restart the foreground service with exponential backoff.
     */
    private fun attemptServiceRecovery() {
        val currentTime = System.currentTimeMillis()

        var restartCount = getRestartCount()
        val lastRestartTime = getLastRestartTime()
        val resetTime = getRestartResetTime()

        // Reset counter if the reset interval has elapsed
        if (currentTime - resetTime > RESTART_RESET_INTERVAL_MS) {
            Logger.d(TAG, "Reset interval passed - resetting restart counter")
            resetRestartTracking()
            restartCount = 0
        }

        // Permanent failure gate
        if (healthPrefs.getBoolean(PREF_PERMANENT_FAILURE, false)) {
            Logger.d(TAG, "In permanent failure state - user intervention required")
            return
        }

        // Max-attempt gate
        if (restartCount >= MAX_RESTART_ATTEMPTS) {
            Logger.e(TAG, "Max restart attempts ($MAX_RESTART_ATTEMPTS) reached - entering permanent failure state")
            healthPrefs.edit().putBoolean(PREF_PERMANENT_FAILURE, true).apply()
            showCriticalServiceFailureNotification()
            return
        }

        // Exponential backoff: 0s, 10s, 30s, 60s, 120s
        val backoffDelayMs = when (restartCount) {
            0 -> 0L
            1 -> 10000L
            2 -> 30000L
            3 -> 60000L
            else -> 120000L
        }

        if (currentTime - lastRestartTime < backoffDelayMs) {
            val remainingWait = backoffDelayMs - (currentTime - lastRestartTime)
            Logger.d(TAG, "Backoff active - waiting ${remainingWait}ms before restart")
            return
        }

        try {
            Logger.d(TAG, "Attempting service restart (attempt ${restartCount + 1}/$MAX_RESTART_ATTEMPTS)")

            val serviceIntent = Intent()
                .setComponent(ComponentName(context, "com.ankit.blocker.services.BlockerForegroundService"))
                .setAction(ServiceBinder.ACTION_START_BLOCKER_SERVICE)

            context.startForegroundService(serviceIntent)

            // Batch count increment + timestamp into one transaction
            recordRestartAttempt(currentTime)

            Logger.d(TAG, "Service restart initiated successfully")
            showServiceRecoveryNotification(restartCount + 1)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to restart foreground service", e)
            showCriticalServiceFailureNotification()
        }
    }

    // ─── Preference accessors ──────────────────────────────────────────────────

    private fun getRestartCount(): Int = healthPrefs.getInt(PREF_RESTART_COUNT, 0)

    private fun getLastRestartTime(): Long = healthPrefs.getLong(PREF_LAST_RESTART_TIME, 0L)

    private fun getRestartResetTime(): Long = healthPrefs.getLong(PREF_RESTART_RESET_TIME, System.currentTimeMillis())

    /** Increments the restart count and records the attempt time in a single transaction. */
    private fun recordRestartAttempt(time: Long) {
        val currentCount = healthPrefs.getInt(PREF_RESTART_COUNT, 0)
        healthPrefs.edit()
            .putInt(PREF_RESTART_COUNT, currentCount + 1)
            .putLong(PREF_LAST_RESTART_TIME, time)
            .apply()
    }

    /** Resets all restart tracking counters. */
    private fun resetRestartTracking() {
        healthPrefs.edit()
            .putInt(PREF_RESTART_COUNT, 0)
            .putLong(PREF_LAST_RESTART_TIME, 0L)
            .putLong(PREF_RESTART_RESET_TIME, System.currentTimeMillis())
            .apply()
    }
}
