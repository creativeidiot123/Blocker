package com.ankit.blocker.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * Helper class for managing application preferences and settings.
 *
 * Uses device-encrypted storage for boot-critical preferences (protection_enabled)
 * to ensure availability during direct boot, while keeping sensitive data
 * (passwords) in credential-encrypted storage for security.
 */
object PreferencesHelper {
    private const val PREF_NAME = "blocker_preferences"
    private const val DEVICE_ENCRYPTED_PREF_NAME = "blocker_device_prefs"
    private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    private const val KEY_PASSWORD_HASH = "password_hash"
    private const val KEY_PASSWORD_SALT = "password_salt"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LAST_FAILED_ATTEMPT = "last_failed_attempt"
    private const val KEY_CRASH_INFO = "crash_info"
    private const val KEY_LAST_UPDATE_VERSION = "last_update_version"
    private const val KEY_BOOT_RECOVERY_STATUS = "boot_recovery_status"
    private const val KEY_BOOT_RECOVERY_TIMESTAMP = "boot_recovery_timestamp"
    private const val KEY_MIGRATION_COMPLETE = "migration_complete"
    
    private const val KEY_TOGGLE_PROCESSING = "toggle_processing"
    private const val KEY_TOGGLE_TIMEOUT = "toggle_timeout"

    // Anki Blocker Keys
    private const val KEY_ANKI_ENABLED = "anki_enabled"
    private const val KEY_ANKI_BLOCKED_APPS = "anki_blocked_apps"

    // Migration flag - cached after first check to avoid repeated migration attempts
    @Volatile
    private var migrationCompleted: Boolean? = null

    // Memory Cache for Anki blocked apps
    private val cachedAnkiBlockedApps = java.util.concurrent.atomic.AtomicReference<Set<String>?>(null)
    private val cacheVersion = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Gets credential-encrypted preferences (default storage).
     * Used for sensitive data like passwords.
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets device-encrypted preferences for direct boot availability.
     * Used for boot-critical preferences like protection_enabled.
     */
    private fun getDeviceProtectedPreferences(context: Context): SharedPreferences {
        val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!context.isDeviceProtectedStorage) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
        } else {
            context
        }
        return deviceContext.getSharedPreferences(DEVICE_ENCRYPTED_PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Sets whether protection is currently enabled.
     * Stored in device-encrypted storage for direct boot access.
     *
     * @param context The application context.
     * @param enabled True to enable protection, false to disable.
     */
    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        getDeviceProtectedPreferences(context).edit()
            .putBoolean(KEY_PROTECTION_ENABLED, enabled)
            // OPTIMIZED: Use apply() for async persistence to avoid potential main-thread ANR
            .apply()
    }
    
    /**
     * Sets the toggle processing state.
     * Used to prevent race conditions even if app is killed.
     */
    fun setToggleProcessing(context: Context, isProcessing: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_TOGGLE_PROCESSING, isProcessing)
            .apply()
    }

    /**
     * Gets the toggle processing state.
     */
    fun isToggleProcessing(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_TOGGLE_PROCESSING, false)
    }

    /**
     * Sets the toggle timeout timestamp.
     */
    fun setToggleTimeout(context: Context, timeout: Long) {
        getPreferences(context).edit()
            .putLong(KEY_TOGGLE_TIMEOUT, timeout)
            .apply()
    }

    /**
     * Gets the toggle timeout timestamp.
     */
    fun getToggleTimeout(context: Context): Long {
        return getPreferences(context).getLong(KEY_TOGGLE_TIMEOUT, 0L)
    }

    /**
     * Gets whether protection is currently enabled.
     * Read from device-encrypted storage to support direct boot.
     * Automatically migrates from old storage on first access (one-time only).
     *
     * @param context The application context.
     * @return True if protection is enabled, false otherwise.
     */
    fun isProtectionEnabled(context: Context): Boolean {
        val devicePrefs = getDeviceProtectedPreferences(context)

        // OPTIMIZED: Use cached migration flag to avoid repeated checks
        if (migrationCompleted != true) {
            // Need to check/perform migration
            synchronized(this) {
                // Double-check inside synchronized block
                if (migrationCompleted != true) {
                    val alreadyMigrated = devicePrefs.getBoolean(KEY_MIGRATION_COMPLETE, false)
                    if (!alreadyMigrated) {
                        try {
                            // Check if value exists in old storage
                            // Note: We use the OLD prefs to check for old value
                            val oldPrefs = getPreferences(context)
                            
                            // Only migrate if we actually have data to migrate
                            if (oldPrefs.contains(KEY_PROTECTION_ENABLED)) {
                                val oldValue = oldPrefs.getBoolean(KEY_PROTECTION_ENABLED, false)
                                
                                // Migrate to new storage and MARK COMPLETE
                                devicePrefs.edit()
                                    .putBoolean(KEY_PROTECTION_ENABLED, oldValue)
                                    .putBoolean(KEY_MIGRATION_COMPLETE, true)
                                    .apply() // Changed to apply()
                            } else {
                                // Nothing to migrate, just mark complete
                                devicePrefs.edit()
                                    .putBoolean(KEY_MIGRATION_COMPLETE, true)
                                    .apply()
                            }
                        } catch (e: Exception) {
                            // Log error but allow access to fallback/default
                            // We don't set migrationCompleted=true here so we retry later
                            return devicePrefs.getBoolean(KEY_PROTECTION_ENABLED, false)
                        }
                    }
                    migrationCompleted = true
                }
            }
        }

        return devicePrefs.getBoolean(KEY_PROTECTION_ENABLED, false)
    }

    // Password-related methods

    /**
     * Sets the password hash for protection deactivation.
     *
     * @param context The application context.
     * @param hash The hashed password to store.
     */
    fun setPasswordHash(context: Context, hash: String) {
        getPreferences(context).edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .apply()
    }

    /**
     * Gets the stored password hash.
     *
     * @param context The application context.
     * @return The stored password hash, or null if not set.
     */
    fun getPasswordHash(context: Context): String? {
        return getPreferences(context).getString(KEY_PASSWORD_HASH, null)
    }

    /**
     * Sets the password salt used for hashing.
     *
     * @param context The application context.
     * @param salt The salt to store.
     */
    fun setPasswordSalt(context: Context, salt: String) {
        getPreferences(context).edit()
            .putString(KEY_PASSWORD_SALT, salt)
            .apply()
    }

    /**
     * Gets the stored password salt.
     *
     * @param context The application context.
     * @return The stored password salt, or null if not set.
     */
    fun getPasswordSalt(context: Context): String? {
        return getPreferences(context).getString(KEY_PASSWORD_SALT, null)
    }

    /**
     * Checks if a password is set for protection.
     *
     * @param context The application context.
     * @return True if password is set, false otherwise.
     */
    fun isPasswordSet(context: Context): Boolean {
        return getPasswordHash(context) != null && getPasswordSalt(context) != null
    }

    /**
     * Clears the stored password (for reset functionality).
     *
     * @param context The application context.
     */
    fun clearPassword(context: Context) {
        getPreferences(context).edit()
            .remove(KEY_PASSWORD_HASH)
            .remove(KEY_PASSWORD_SALT)
            .apply()
    }

    // Failed attempt tracking methods

    /**
     * Sets the number of failed password attempts.
     *
     * @param context The application context.
     * @param attempts Number of failed attempts.
     */
    fun setFailedPasswordAttempts(context: Context, attempts: Int) {
        getPreferences(context).edit()
            .putInt(KEY_FAILED_ATTEMPTS, attempts)
            .apply()
    }

    /**
     * Gets the number of failed password attempts.
     *
     * @param context The application context.
     * @return Number of failed attempts.
     */
    fun getFailedPasswordAttempts(context: Context): Int {
        return getPreferences(context).getInt(KEY_FAILED_ATTEMPTS, 0)
    }

    /**
     * Sets the timestamp of the last failed password attempt.
     *
     * @param context The application context.
     * @param timestamp Timestamp in milliseconds.
     */
    fun setLastFailedAttemptTime(context: Context, timestamp: Long) {
        getPreferences(context).edit()
            .putLong(KEY_LAST_FAILED_ATTEMPT, timestamp)
            .apply()
    }

    /**
     * Gets the timestamp of the last failed password attempt.
     *
     * @param context The application context.
     * @return Timestamp in milliseconds, or 0 if no failed attempts.
     */
    fun getLastFailedAttemptTime(context: Context): Long {
        return getPreferences(context).getLong(KEY_LAST_FAILED_ATTEMPT, 0)
    }

    // Boot recovery and crash tracking methods

    /**
     * Stores crash information for debugging purposes.
     *
     * @param context The application context.
     * @param component The component where the crash occurred.
     * @param error The error message.
     */
    fun storeCrashInfo(context: Context, component: String, error: String) {
        val timestamp = System.currentTimeMillis()
        val crashInfo = "$timestamp|$component|$error"

        getPreferences(context).edit()
            .putString(KEY_CRASH_INFO, crashInfo)
            .apply()
    }

    /**
     * Gets the last stored crash information.
     *
     * @param context The application context.
     * @return The crash information string, or null if none stored.
     */
    fun getLastCrashInfo(context: Context): String? {
        return getPreferences(context).getString(KEY_CRASH_INFO, null)
    }

    /**
     * Stores information about app updates.
     *
     * @param context The application context.
     * @param versionCode The new version code.
     */
    fun storeLastUpdateInfo(context: Context, versionCode: Long) {
        getPreferences(context).edit()
            .putLong(KEY_LAST_UPDATE_VERSION, versionCode)
            .apply()
    }

    /**
     * Gets the last update version code.
     *
     * @param context The application context.
     * @return The version code of the last update.
     */
    fun getLastUpdateVersion(context: Context): Long {
        return getPreferences(context).getLong(KEY_LAST_UPDATE_VERSION, 0)
    }

    /**
     * Stores boot recovery status for debugging.
     *
     * @param context The application context.
     * @param accessibilityServiceRunning Whether accessibility service is running.
     * @param foregroundServiceRunning Whether foreground service is running.
     * @param hasAccessibilityPermission Whether accessibility permission is granted.
     * @param hasDeviceAdminPermission Whether device admin permission is granted.
     */
    fun storeBootRecoveryStatus(
        context: Context,
        accessibilityServiceRunning: Boolean,
        foregroundServiceRunning: Boolean,
        hasAccessibilityPermission: Boolean,
        hasDeviceAdminPermission: Boolean
    ) {
        val status = "$accessibilityServiceRunning|$foregroundServiceRunning|$hasAccessibilityPermission|$hasDeviceAdminPermission"
        val timestamp = System.currentTimeMillis()

        getPreferences(context).edit()
            .putString(KEY_BOOT_RECOVERY_STATUS, status)
            .putLong(KEY_BOOT_RECOVERY_TIMESTAMP, timestamp)
            .apply()
    }

    /**
     * Gets the last boot recovery status.
     *
     * @param context The application context.
     * @return The boot recovery status string, or null if none stored.
     */
    fun getLastBootRecoveryStatus(context: Context): String? {
        return getPreferences(context).getString(KEY_BOOT_RECOVERY_STATUS, null)
    }

    /**
     * Gets the timestamp of the last boot recovery.
     *
     * @param context The application context.
     * @return Timestamp in milliseconds, or 0 if no recovery recorded.
     */
    fun getLastBootRecoveryTimestamp(context: Context): Long {
        return getPreferences(context).getLong(KEY_BOOT_RECOVERY_TIMESTAMP, 0)
    }

    // Blocklist Cache Methods

    /**
     * Invalidates all internal blocklist caches.
     * Should be called when blocklists are updated to force reload.
     */
    fun invalidateBlocklistCaches() {
        cachedAnkiBlockedApps.set(null)
        cacheVersion.incrementAndGet()
        Logger.d("PreferencesHelper", "Blocklist caches invalidated, version: ${cacheVersion.get()}")
    }

    // Anki Blocker Methods

    fun isAnkiBlockerEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_ANKI_ENABLED, false)
    }

    fun setAnkiBlockerEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_ANKI_ENABLED, enabled).apply()
        // Notify service cache to refresh
        invalidateBlocklistCaches()
    }

    fun getAnkiBlockedApps(context: Context): Set<String> {
        return cachedAnkiBlockedApps.updateAndGet { cache ->
            if (cache != null) return@updateAndGet cache
            getPreferences(context).getStringSet(KEY_ANKI_BLOCKED_APPS, emptySet())?.toSet() ?: emptySet()
        } ?: emptySet()
    }

    fun setAnkiBlockedApps(context: Context, apps: Set<String>) {
        getPreferences(context).edit().putStringSet(KEY_ANKI_BLOCKED_APPS, HashSet(apps)).apply()
        // Immediately invalidate cache so next read reflects the change
        cachedAnkiBlockedApps.set(null)
        cacheVersion.incrementAndGet()
    }

    /**
     * Atomically writes password hash AND salt in a single editor transaction.
     * Prevents partial writes if the process is killed between two separate apply() calls.
     *
     * @param context The application context.
     * @param hash The hashed password.
     * @param salt The salt used for hashing.
     */
    fun setPasswordHashAndSalt(context: Context, hash: String, salt: String) {
        getPreferences(context).edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .putString(KEY_PASSWORD_SALT, salt)
            .apply()
    }
}