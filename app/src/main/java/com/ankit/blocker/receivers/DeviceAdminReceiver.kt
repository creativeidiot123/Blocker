package com.ankit.blocker.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.ankit.blocker.services.BlockerAccessibilityService
import com.ankit.blocker.utils.ToastManager
import com.ankit.blocker.utils.Utils

/**
 * A DeviceAdminReceiver for handling device administration events for the Blocker app.
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        const val ACTION_TAMPER_PROTECTION_CHANGED = "com.ankit.blocker.action.tamperProtectionChanged"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        ToastManager.show(context, "Settings protection enabled")
        refreshProtectionSettings(context)
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        ToastManager.show(context, "Settings protection disabled")
        refreshProtectionSettings(context)
        super.onDisabled(context, intent)
    }

    private fun refreshProtectionSettings(context: Context) {
        if (Utils.isServiceRunning(context, BlockerAccessibilityService::class.java)) {
            val serviceIntent = Intent(
                context.applicationContext,
                BlockerAccessibilityService::class.java
            ).setAction(ACTION_TAMPER_PROTECTION_CHANGED)

            context.startService(serviceIntent)
        }
    }
}