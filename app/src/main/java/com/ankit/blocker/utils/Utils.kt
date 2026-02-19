package com.ankit.blocker.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object Utils {
    private const val TAG = "Blocker.Utils"

    /**
     * Checks if a specific accessibility service is currently enabled.
     *
     * @param context The application context.
     * @param serviceClass The class of the accessibility service to check.
     * @return True if the service is enabled, false otherwise.
     */
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return isAccessibilityServiceEnabled(context, serviceClass) ||
               isAccessibilityServiceEnabledAlternative(context, serviceClass)
    }

    /**
     * Checks if a specific accessibility service is currently enabled using service class name.
     * This overload helps avoid circular dependency issues.
     *
     * @param context The application context.
     * @param serviceClassName The full class name of the accessibility service to check.
     * @return True if the service is enabled, false otherwise.
     */
    fun isServiceRunning(context: Context, serviceClassName: String): Boolean {
        return try {
            val serviceClass = Class.forName(serviceClassName)
            isServiceRunning(context, serviceClass)
        } catch (e: ClassNotFoundException) {
            Logger.e(TAG, "Service class not found: $serviceClassName", e)
            false
        }
    }

    /**
     * Primary method to check if accessibility service is enabled using AccessibilityManager
     */
    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        try {
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

            val expectedServiceId = "${context.packageName}/${serviceClass.name}"
            val alternativeServiceId = "${context.packageName}/.${serviceClass.simpleName}"

            return enabledServices.any { service ->
                val serviceId = service.id
                val serviceName = service.resolveInfo?.serviceInfo?.name
                serviceId == expectedServiceId ||
                serviceId == alternativeServiceId ||
                serviceName == serviceClass.name
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error checking accessibility service status (method 1)", e)
            return false
        }
    }

    /**
     * Alternative method using Settings.Secure to check accessibility services
     */
    private fun isAccessibilityServiceEnabledAlternative(context: Context, serviceClass: Class<*>): Boolean {
        try {
            val enabledServicesString = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedServiceName1 = "${context.packageName}/${serviceClass.name}"
            val expectedServiceName2 = "${context.packageName}/.${serviceClass.simpleName}"

            val enabledServices = enabledServicesString.split(":")
            return enabledServices.any { serviceName ->
                serviceName.trim() == expectedServiceName1 || serviceName.trim() == expectedServiceName2
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error checking accessibility service status (method 2)", e)
            return false
        }
    }

}