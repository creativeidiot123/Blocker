package com.ankit.blocker.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ankit.blocker.generics.ServiceBinder
import com.ankit.blocker.helpers.NotificationHelper
import com.ankit.blocker.utils.Logger
import com.ankit.blocker.utils.PermissionsHelper
import com.ankit.blocker.utils.PreferencesHelper
import com.ankit.blocker.workers.ServiceHealthWorker
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Foreground service that maintains protection by monitoring accessibility service status
 * and ensuring critical services remain active across reboots and system changes.
 *
 * Refactored to use Kotlin Coroutines for efficient background monitoring.
 */
class BlockerForegroundService : Service() {

    companion object {
        private const val TAG = "Blocker.ForegroundService"
        


        // Action to recreate notification without restarting monitoring
        const val ACTION_RECREATE_NOTIFICATION = "com.ankit.blocker.action.RECREATE_NOTIFICATION"

        /**
         * Starts the Blocker foreground service.
         */
        fun startService(context: Context) {
            try {
                val intent = Intent(context, BlockerForegroundService::class.java)
                    .setAction(ServiceBinder.ACTION_START_BLOCKER_SERVICE)
                Logger.d(TAG, "Requesting start of BlockerForegroundService with intent: $intent")
                context.startForegroundService(intent)
                Logger.d(TAG, "Requested start of BlockerForegroundService")

            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start BlockerForegroundService", e)
            }
        }

        /**
         * Recreates the notification if service is already running.
         * Used when user dismisses notification but service should stay active.
         */
        fun recreateNotification(context: Context) {
            try {
                val intent = Intent(context, BlockerForegroundService::class.java)
                    .setAction(ACTION_RECREATE_NOTIFICATION)
                Logger.d(TAG, "Requesting notification recreation")
                context.startService(intent)
                Logger.d(TAG, "Notification recreation requested")

            } catch (e: Exception) {
                Logger.e(TAG, "Failed to recreate notification", e)
            }
        }

        /**
         * Stops the Blocker foreground service.
         */
        fun stopService(context: Context) {
            try {
                val intent = Intent(context, BlockerForegroundService::class.java)
                    .setAction("STOP_SERVICE")
                Logger.d(TAG, "Requesting stop of BlockerForegroundService with intent: $intent")
                context.startService(intent)
                Logger.d(TAG, "Requested stop of BlockerForegroundService")

            } catch (e: Exception) {
                Logger.e(TAG, "Failed to stop BlockerForegroundService", e)
            }
        }

        // Efficient state tracking avoids deprecated getRunningServices
        // OPTIMIZED: Use AtomicBoolean for proper thread safety
        private val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Checks if the foreground service is currently running.
         */
        fun isServiceRunning(context: Context): Boolean {
            return isRunning.get()
        }
    }

    private val serviceBinder = ServiceBinder(this)
    
    // Coroutine Scope for service lifecycle
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        Logger.d(TAG, "BlockerForegroundService created.")

        // Register notification channels if not already done
        NotificationHelper.registerNotificationChannels(this)
        Logger.d(TAG, "Notification channels registered on create.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(TAG, "onStartCommand called with action: ${intent?.action}, flags: $flags, startId: $startId")

        when (intent?.action) {
            ServiceBinder.ACTION_START_BLOCKER_SERVICE -> {
                Logger.d(TAG, "Starting foreground service and monitoring.")
                startForegroundService()
                return START_STICKY
            }
            ACTION_RECREATE_NOTIFICATION -> {
                Logger.d(TAG, "Recreating notification (service already running)")
                // Just recreate the notification without restarting monitoring
                recreateNotificationOnly()
                return START_STICKY
            }
            "STOP_SERVICE" -> {
                // Only stop if protection is actually disabled by user
                val isProtectionEnabled = PreferencesHelper.isProtectionEnabled(this)
                if (!isProtectionEnabled) {
                    Logger.d(TAG, "Protection disabled by user - stopping foreground service")
                    stopForegroundService()
                    return START_NOT_STICKY
                } else {
                    Logger.d(TAG, "Protection still enabled - ignoring stop request to prevent restart loops")
                    return START_STICKY
                }
            }
            else -> {
                Logger.d(TAG, "Default behavior - starting foreground service and monitoring.")
                
                // CRITICAL: Check if we have required permissions. If not, this might be a restart after update/crash/force-stop
                // where Android disabled permissions. We must prompt the user to re-enable them.
                if (!PermissionsHelper.areAllPermissionsGranted(this)) {
                    Logger.w(TAG, "Service started but critical permissions are MISSING! Prompting user recovery.")
                    NotificationHelper.showPostUpdatePermissionRecoveryNotification(this)
                }
                
                // Default behavior - start the service
                startForegroundService()
                return START_STICKY
            }
        }
    }

    /**
     * Starts the foreground service with appropriate notification.
     */
    private fun startForegroundService() {
        try {
            Logger.d(TAG, "Attempting to start foreground service.")
            // Ensure notification channels are registered (defensive programming)
            NotificationHelper.registerNotificationChannels(this@BlockerForegroundService)

            val notification = NotificationHelper.buildForegroundServiceNotification(
                this@BlockerForegroundService,
                "Blocker Protection Active",
                "Settings protection is running and monitoring for threats"
            )

            // CRITICAL: Must be called synchronously within 5 seconds of service start
            startForeground(NotificationHelper.FOREGROUND_SERVICE_NOTIFICATION_ID, notification)
            Logger.d(TAG, "Foreground service started successfully")

            // Schedule hourly health check worker
            scheduleHealthCheckWorker()

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start foreground service", e)
            // Try to stop the service gracefully if we can't start it properly
            stopSelf()
        }
    }



    /**
     * Recreates the notification without restarting monitoring.
     * Used when service is already running but notification was dismissed.
     */
    private fun recreateNotificationOnly() {
        try {
            Logger.d(TAG, "Recreating notification only (no service restart)")
            val notification = NotificationHelper.buildForegroundServiceNotification(
                this@BlockerForegroundService,
                "Blocker Protection Active",
                "Settings protection is running and monitoring for threats"
            )

            startForeground(NotificationHelper.FOREGROUND_SERVICE_NOTIFICATION_ID, notification)
            Logger.d(TAG, "Notification recreated successfully")

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to recreate notification", e)
        }
    }

    /**
     * Schedules the hourly health check worker using WorkManager.
     * This runs every 1 hour to ensure the service stays alive.
     */
    private fun scheduleHealthCheckWorker() {
        try {
            Logger.d(TAG, "Scheduling hourly health check worker")

            // Create constraints - run even in Doze mode
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Run even on low battery
                .setRequiresCharging(false)      // Run even when not charging
                .build()

            // Create periodic work request - runs every 15 minutes (minimum allowed by Android)
            // Reduced to 1 hour for faster detection of OEM-killed accessibility services
            val healthCheckRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                1, TimeUnit.HOURS  // 1 hour for timely accessibility service monitoring
            )
                .setConstraints(constraints)
                .build()

            // Enqueue with REPLACE policy to avoid duplicate workers
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                ServiceHealthWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                healthCheckRequest
            )

            Logger.d(TAG, "Hourly health check worker scheduled successfully")

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to schedule health check worker", e)
        }
    }

    /**
     * Cancels the hourly health check worker.
     */
    private fun cancelHealthCheckWorker() {
        try {
            Logger.d(TAG, "Cancelling hourly health check worker")
            WorkManager.getInstance(applicationContext)
                .cancelUniqueWork(ServiceHealthWorker.UNIQUE_WORK_NAME)
            Logger.d(TAG, "Health check worker cancelled")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to cancel health check worker", e)
        }
    }

    /**
     * Stops the foreground service.
     */
    private fun stopForegroundService() {
        try {
            Logger.d(TAG, "Stopping foreground service.")
            cancelHealthCheckWorker() // Cancel hourly health check
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf() // Actually stop the service
            Logger.d(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Logger.e(TAG, "Error stopping foreground service", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Logger.d(TAG, "Service bind requested with action: ${intent?.action}")
        return when (intent?.action) {
            ServiceBinder.ACTION_BIND_TO_BLOCKER -> serviceBinder
            else -> null
        }
    }

    override fun onDestroy() {
        try {
            Logger.d(TAG, "BlockerForegroundService being destroyed")
            isRunning.set(false)
            serviceScope.cancel() // Cancel all coroutines
            super.onDestroy()
        } catch (e: Exception) {
            Logger.e(TAG, "Error during service destruction", e)
        }
    }

}