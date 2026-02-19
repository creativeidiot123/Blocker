package com.ankit.blocker.workers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ankit.blocker.generics.ServiceBinder
import com.ankit.blocker.helpers.NotificationHelper
import com.ankit.blocker.services.BlockerForegroundService
import com.ankit.blocker.utils.Logger
import com.ankit.blocker.utils.PermissionsHelper
import com.ankit.blocker.utils.PreferencesHelper
import com.ankit.blocker.utils.Utils
import java.util.concurrent.TimeUnit

/**
 * Background worker that handles post-boot initialization tasks to ensure
 * Blocker protection services are properly restored after device restart.
 *
 * This worker is executed via WorkManager to ensure reliable execution
 * even under Android's Doze mode and background restrictions.
 */
class BootWorker(
    private val context: Context,
    private val workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "Blocker.BootWorker"
        const val BOOT_TASK_ID = "com.ankit.blocker.bootTaskId"
        const val UNIQUE_WORK_NAME = "BlockerBootRecovery"
    }

    override fun doWork(): Result {
        val taskId = workerParams.inputData.getString(BOOT_TASK_ID) ?: "unknown"
        Logger.d(TAG, "BootWorker started. Task ID: $taskId")
        return try {
            Logger.d(TAG, "Starting boot recovery task: $taskId")

            when (taskId) {
                "onBootCompleted" -> handleBootCompleted()
                "onPackageReplaced" -> handlePackageReplaced()
                else -> {
                    Logger.w(TAG, "Unknown boot task: $taskId, defaulting to handleBootCompleted")
                    handleBootCompleted() // Default to boot completed
                }
            }

            Logger.d(TAG, "Boot recovery task completed successfully: $taskId")
            Result.success()

        } catch (e: Exception) {
            Logger.e(TAG, "Boot recovery task failed for taskId: $taskId", e)
            // Store crash info for later analysis
            PreferencesHelper.storeCrashInfo(context, "BootWorker", e.message ?: "Unknown error")
            Result.failure()
        }
    }

    /**
     * Handles device boot completion by restoring all protection services.
     */
    private fun handleBootCompleted() {
        Logger.d(TAG, "Handling device boot completion")

        try {
            // Step 1: Register notification channels (required before starting foreground services)
            // The delay for channel propagation is handled by WorkManager's setInitialDelay()
            NotificationHelper.registerNotificationChannels(context)
            Logger.d(TAG, "Notification channels registered")

            // Step 2: Check if protection was enabled before reboot
            val wasProtectionEnabled = PreferencesHelper.isProtectionEnabled(context)
            Logger.d(TAG, "Protection was enabled before reboot: $wasProtectionEnabled")

            if (!wasProtectionEnabled) {
                Logger.d(TAG, "Protection was disabled before reboot - skipping service restoration")
                return
            }

            // Step 3: Start foreground service first - it's responsible for monitoring state
            // The foreground service should start regardless of permission status if protection
            // was enabled. The service itself will handle permission checks and notifications.
            Logger.d(TAG, "Starting foreground service to restore protection")
            try {
                val serviceIntent = Intent()
                    .setComponent(ComponentName(context, "com.ankit.blocker.services.BlockerForegroundService"))
                    .setAction(ServiceBinder.ACTION_START_BLOCKER_SERVICE)
                Logger.d(TAG, "Created service intent: $serviceIntent, component: ${serviceIntent.component}")
                context.startForegroundService(serviceIntent)
                Logger.d(TAG, "Foreground service start requested - it will handle permission monitoring")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start foreground service", e)
            }

            // Step 4: Schedule hourly health check worker as backup monitoring
            scheduleHealthCheckWorker()

            // Step 5: Log final status
            logBootRecoveryStatus()

        } catch (e: Exception) {
            Logger.e(TAG, "Error during boot completion handling", e)
            PreferencesHelper.storeCrashInfo(context, "BootWorker.handleBootCompleted", e.message ?: "Unknown error")
        }
    }

    /**
     * Schedules the hourly health check worker as a backup monitoring system.
     * This ensures the foreground service stays alive even if it wasn't properly scheduled by the service itself.
     */
    private fun scheduleHealthCheckWorker() {
        try {
            Logger.d(TAG, "Scheduling hourly health check worker from BootWorker")

            // Create constraints - run even in Doze mode
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .setRequiresCharging(false)
                .build()

            // Create periodic work request - runs every 1 hour
            val healthCheckRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            // Enqueue with REPLACE policy to avoid duplicate workers
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                ServiceHealthWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                healthCheckRequest
            )

            Logger.d(TAG, "Hourly health check worker scheduled successfully from BootWorker")

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to schedule health check worker from BootWorker", e)
        }
    }

    /**
     * Handles package replacement events (app updates).
     */
    private fun handlePackageReplaced() {
        Logger.d(TAG, "Handling package replacement (app update)")

        try {
            // Similar to boot completion but may need different handling
            handleBootCompleted()

            // Additional steps specific to app updates
            val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager
                    .getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    .longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode
            }

            Logger.d(TAG, "App updated to version code: $currentVersionCode")

            // Store the update info for analytics/debugging
            PreferencesHelper.storeLastUpdateInfo(context, currentVersionCode)

        } catch (e: Exception) {
            Logger.e(TAG, "Error during package replacement handling", e)
            PreferencesHelper.storeCrashInfo(context, "BootWorker.handlePackageReplaced", e.message ?: "Unknown error")
        }
    }

    /**
     * Logs the initial status of boot recovery for debugging purposes.
     * Note: Services may still be starting, so this is a snapshot at boot completion time.
     */
    private fun logBootRecoveryStatus() {
        try {
            val isAccessibilityServiceRunning = Utils.isServiceRunning(context, "com.ankit.blocker.services.BlockerAccessibilityService")
            val isForegroundServiceRunning = BlockerForegroundService.isServiceRunning(context)
            val hasAccessibilityPermission = PermissionsHelper.getAndAskAccessibilityPermission(context, false)
            val hasDeviceAdminPermission = PermissionsHelper.getAndAskAdminPermission(context, false)
            val isProtectionEnabled = PreferencesHelper.isProtectionEnabled(context)

            Logger.d(TAG, "=== Boot Recovery Initial Status ===")
            Logger.d(TAG, "Accessibility Service Running: $isAccessibilityServiceRunning (may still be starting)")
            Logger.d(TAG, "Foreground Service Running: $isForegroundServiceRunning (may still be starting)")
            Logger.d(TAG, "Accessibility Permission: $hasAccessibilityPermission")
            Logger.d(TAG, "Device Admin Permission: $hasDeviceAdminPermission")
            Logger.d(TAG, "Protection Enabled: $isProtectionEnabled")
            Logger.d(TAG, "Note: ForegroundService will monitor and report actual status in ~30 seconds")
            Logger.d(TAG, "=== End Initial Status ===")

            // Store initial recovery status for later analysis
            PreferencesHelper.storeBootRecoveryStatus(
                context,
                isAccessibilityServiceRunning,
                isForegroundServiceRunning,
                hasAccessibilityPermission,
                hasDeviceAdminPermission
            )

        } catch (e: Exception) {
            Logger.e(TAG, "Error logging boot recovery status", e)
        }
    }
}