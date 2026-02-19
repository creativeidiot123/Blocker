package com.ankit.blocker.utils

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ankit.blocker.R
import com.ankit.blocker.receivers.DeviceAdminReceiver
import com.ankit.blocker.services.BlockerAccessibilityService

/**
 * PermissionsHelper provides utility methods for managing and requesting necessary permissions
 * for the Blocker application. It includes methods to check and request permissions for accessibility
 * and device administration.
 */
object PermissionsHelper {
    private const val TAG = "Blocker.PermissionsHelper"

    /**
     * Checks if the device administration permission is granted and optionally asks for it if not granted.
     *
     * @param context The application context used to check permissions and start activities.
     * @param askPermissionToo Whether to prompt the user to enable device administration permission if not granted.
     * @return True if device administration permission is granted, false otherwise.
     */
    fun getAndAskAdminPermission(context: Context, askPermissionToo: Boolean): Boolean {
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (devicePolicyManager.isAdminActive(componentName)) {
            return true
        }

        if (askPermissionToo) {
            try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        context.getString(R.string.admin_description)
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Logger.e(TAG, "Unable to open device ADMIN settings", e)
            }
        }
        return false
    }

    /**
     * Checks if the accessibility permission is granted and optionally asks for it if not granted.
     *
     * @param context The application context used to check permissions and start activities.
     * @param askPermissionToo Whether to prompt the user to enable accessibility permission if not granted.
     * @return True if accessibility permission is granted, false otherwise.
     */
    fun getAndAskAccessibilityPermission(context: Context, askPermissionToo: Boolean): Boolean {
        if (Utils.isServiceRunning(context, BlockerAccessibilityService::class.java)) {
            return true
        }

        if (askPermissionToo) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Logger.e(TAG, "Unable to open accessibility settings", e)
            }
        }
        return false
    }

    /**
     * Checks if both required permissions are granted.
     *
     * @param context The application context.
     * @return True if both admin and accessibility permissions are granted, false otherwise.
     */
    fun areAllPermissionsGranted(context: Context): Boolean {
        return getAndAskAdminPermission(context, false) &&
               getAndAskAccessibilityPermission(context, false)
    }

    /**
     * Checks if POST_NOTIFICATIONS permission is granted (Android 13+).
     *
     * @param context The application context.
     * @return True if permission is granted or not required (Android < 13), false otherwise.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on Android < 13
        }
    }

    /**
     * Requests POST_NOTIFICATIONS permission (Android 13+).
     * Should be called from an Activity context.
     *
     * @param activity The activity from which to request permission.
     * @param requestCode The request code to use for the permission request.
     */
    fun requestNotificationPermission(activity: Activity, requestCode: Int = 1001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission(activity)) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    requestCode
                )
                Logger.d(TAG, "Requested POST_NOTIFICATIONS permission")
            }
        }
    }

    /**
     * Checks if overlay (Draw over other apps) permission is granted.
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Opens the overlay permission settings for this app.
     */
    fun requestOverlayPermission(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Logger.e(TAG, "Unable to open overlay settings", e)
        }
    }
}