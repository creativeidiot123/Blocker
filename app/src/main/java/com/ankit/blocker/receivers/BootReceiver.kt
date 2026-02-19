package com.ankit.blocker.receivers

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.ankit.blocker.generics.ServiceBinder
import com.ankit.blocker.helpers.NotificationHelper
import com.ankit.blocker.utils.Logger
import com.ankit.blocker.utils.PermissionsHelper
import com.ankit.blocker.utils.PreferencesHelper
import com.ankit.blocker.workers.BootWorker
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Enhanced BroadcastReceiver that handles device boot completion and package replacement events
 * to restart protection services using WorkManager for reliable execution under all system states.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "Blocker.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Logger.d(TAG, "BootReceiver triggered. Action: ${intent.action}")
        
        val pendingResult = goAsync()
        
        // OPTIMIZED: Create scope tied to this specific broadcast with timeout to prevent leaks
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                // CRITICAL: 10-second timeout prevents wakelock leak if coroutine hangs
                withTimeout(10000L) {
                    when (intent.action) {
                        Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                            // DE (device-encrypted) storage is available but CE (credential-encrypted)
                            // storage is NOT yet available before first unlock. Only do lightweight work here:
                            // register notification channels and wait for ACTION_BOOT_COMPLETED.
                            Logger.d(TAG, "Locked boot completed – registering channels, deferring to BOOT_COMPLETED")
                            try {
                                NotificationHelper.registerNotificationChannels(context)
                            } catch (e: Exception) {
                                Logger.e(TAG, "Failed to register channels on locked boot", e)
                            }
                        }
                        Intent.ACTION_BOOT_COMPLETED -> {
                            Logger.d(TAG, "Device boot completed (user unlocked) - initiating recovery process")
                            handleBootEvent(context, "onBootCompleted")
                        }
                        Intent.ACTION_MY_PACKAGE_REPLACED -> {
                            // Android guarantees MY_PACKAGE_REPLACED is delivered only to our app
                            Logger.d(TAG, "Package replacement detected – initiating recovery")
                            handleBootEvent(context, "onPackageReplaced")
                        }
                        else -> {
                            Logger.w(TAG, "Received unexpected intent action: ${intent.action}")
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                Logger.e(TAG, "Boot event processing timed out after 10 seconds")
            } finally {
                // Critical: Must call finish() to release the wakelock held by goAsync()
                pendingResult.finish()
                // OPTIMIZED: Clean up scope to prevent memory leak
                scope.cancel()
            }
        }
    }

    /**
     * Handles boot and package replacement events by directly starting foreground service.
     */
    private suspend fun handleBootEvent(context: Context, taskType: String) {
        try {
            Logger.d(TAG, "Processing boot event: $taskType")

            // Step 1: Register notification channels
            // Safe to run here as we are on IO thread thanks to Dispatchers.IO above
            try {
                NotificationHelper.registerNotificationChannels(context)
                Logger.d(TAG, "Notification channels registered for $taskType")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to register channels", e)
            }
            
            val wasProtectionEnabled = try {
                 PreferencesHelper.isProtectionEnabled(context)
            } catch (e: Exception) {
                 Logger.e(TAG, "Error checking preferences during boot", e)
                 true 
            }
            
            Logger.d(TAG, "Protection enabled before reboot: $wasProtectionEnabled for $taskType")

            if (!wasProtectionEnabled) {
                Logger.d(TAG, "Protection was disabled before reboot - skipping service start for $taskType")
                return
            }

            // Step 2: Start foreground service directly
            try {
                val serviceIntent = Intent()
                    .setComponent(ComponentName(context, "com.ankit.blocker.services.BlockerForegroundService"))
                    .setAction(ServiceBinder.ACTION_START_BLOCKER_SERVICE)
                Logger.d(TAG, "Created service intent for $taskType: $serviceIntent")

                // StartForegroundService is allowed from background if app is in whitelist or handling specific broadcasts
                // BOOT_COMPLETED allows it.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Logger.d(TAG, "Foreground service started successfully after boot: $taskType")

                // CRITICAL: Cancel WorkManager fallback to prevent duplicate service instances
                try {
                    WorkManager.getInstance(context).cancelUniqueWork(BootWorker.UNIQUE_WORK_NAME)
                    Logger.d(TAG, "Cancelled WorkManager fallback - service started directly")
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to cancel WorkManager fallback", e)
                }

            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start foreground service directly for $taskType", e)
                // Fallback: Schedule with WorkManager
                scheduleBootWorker(context, taskType)
            }


            // Step 3: PERFORM PERMISSION HEALTH CHECK
            // After update (MY_PACKAGE_REPLACED) or boot, permissions might be disabled.
            // Screen them now and prompt user if needed.
            if (!PermissionsHelper.areAllPermissionsGranted(context)) {
                 Logger.w(TAG, "Boot event processed but critical permissions are MISSING! Prompting user recovery.")
                 NotificationHelper.showPostUpdatePermissionRecoveryNotification(context)
            }

            // Step 4: Store basic recovery attempt info
            PreferencesHelper.storeCrashInfo(
                context,
                "BootReceiver",
                "Boot event handled: $taskType at ${System.currentTimeMillis()}"
            )

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to handle boot event: $taskType", e)
            PreferencesHelper.storeCrashInfo(context, "BootReceiver.handleBootEvent", e.message ?: "Unknown error")
        }
    }

    /**
     * Fallback method to schedule boot recovery using WorkManager.
     */
    private fun scheduleBootWorker(context: Context, taskType: String) {
        try {
            val bootWorkRequest = OneTimeWorkRequest.Builder(BootWorker::class.java)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    Data.Builder()
                        .putString(BootWorker.BOOT_TASK_ID, taskType)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                BootWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                bootWorkRequest
            )

            Logger.d(TAG, "Boot recovery work scheduled as fallback for task: $taskType")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to schedule WorkManager fallback", e)
        }
    }
}