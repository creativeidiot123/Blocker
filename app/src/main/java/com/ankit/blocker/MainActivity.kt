package com.ankit.blocker

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ankit.blocker.generics.ServiceBinder
import com.ankit.blocker.ui.AppSelectionActivity
import com.ankit.blocker.ui.AnkiBlockerActivity
import com.ankit.blocker.helpers.NotificationHelper
import com.ankit.blocker.utils.PermissionsHelper
import com.ankit.blocker.utils.PreferencesHelper
import com.ankit.blocker.utils.PasswordDialog
import com.ankit.blocker.utils.SecurityHelper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Data class to hold permission status for UI updates
 */
data class PermissionStatus(
    val hasAdmin: Boolean,
    val hasAccessibility: Boolean,
    val hasOverlay: Boolean,
    val isProtectionEnabled: Boolean,
    val isProtectionActive: Boolean
)



class MainActivity : AppCompatActivity() {
    private lateinit var adminStatusText: TextView
    private lateinit var accessibilityStatusText: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var protectionStatusText: TextView
    private lateinit var enableAdminButton: Button
    private lateinit var enableAccessibilityButton: Button
    private lateinit var enableOverlayButton: Button
    private lateinit var protectionToggleButton: Button
    private lateinit var ankiBlockerButton: Button

    // Race condition protection with timeout
    // State is now persisted in PreferencesHelper to survive app kills

    // REMOVED: Handler leak fixed - now using lifecycleScope.launchWhenResumed

    companion object {
        internal const val STATUS_UPDATE_INTERVAL_MS = 30000L // 30 seconds for normal checks
        private const val TOGGLE_TIMEOUT_MS = 10000L // 10 second timeout
        internal const val ACTIVE_UPDATE_INTERVAL_MS = 5000L // 5 seconds when actively changing
    }

    // Track last known state to avoid unnecessary updates (synchronized access)
    @Volatile
    private var lastPermissionStatus: PermissionStatus? = null
    internal var isActivelyChanging = false
    internal var activeChangeStartTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize notification channels on app start
        NotificationHelper.registerNotificationChannels(this)

        // Request POST_NOTIFICATIONS permission for Android 13+
        PermissionsHelper.requestNotificationPermission(this)

        initializeViews()
        setupButtonListeners()
        updatePermissionStatus()
    }

    private fun initializeViews() {
        adminStatusText = findViewById(R.id.adminStatusText)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        overlayStatusText = findViewById(R.id.overlayStatusText)
        protectionStatusText = findViewById(R.id.protectionStatusText)
        enableAdminButton = findViewById(R.id.enableAdminButton)
        enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton)
        enableOverlayButton = findViewById(R.id.enableOverlayButton)
        protectionToggleButton = findViewById(R.id.protectionToggleButton)
        ankiBlockerButton = findViewById(R.id.ankiBlockerButton)
    }

    private fun setupButtonListeners() {
        enableAdminButton.setOnClickListener {
            PermissionsHelper.getAndAskAdminPermission(this, true)
            triggerActiveMode() // User is actively changing permissions
        }

        enableAccessibilityButton.setOnClickListener {
            PermissionsHelper.getAndAskAccessibilityPermission(this, true)
            triggerActiveMode() // User is actively changing permissions
        }

        enableOverlayButton.setOnClickListener {
            PermissionsHelper.requestOverlayPermission(this)
            triggerActiveMode()
        }

        protectionToggleButton.setOnClickListener {
            toggleProtection()
        }

        ankiBlockerButton.setOnClickListener {
            startActivity(Intent(this, AnkiBlockerActivity::class.java))
        }
    }

    /**
     * Triggers active mode for faster UI updates when user is making changes
     */
    private fun triggerActiveMode() {
        isActivelyChanging = true
        activeChangeStartTime = System.currentTimeMillis()
        // Force an immediate update
        updatePermissionStatusIfChanged()
    }

    @Synchronized
    private fun toggleProtection() {
        // Prevent race conditions with multiple rapid clicks and add timeout protection
        val currentTime = System.currentTimeMillis()
        
        // Check persistent state
        val isProcessing = PreferencesHelper.isToggleProcessing(this)
        val timeoutTimestamp = PreferencesHelper.getToggleTimeout(this)
        
        if (isProcessing) {
            // Check if toggle has timed out
            if (currentTime - timeoutTimestamp > TOGGLE_TIMEOUT_MS) {
                // Reset stuck state
                PreferencesHelper.setToggleProcessing(this, false)
                PreferencesHelper.setToggleTimeout(this, 0L)
            } else {
                return
            }
        }

        // Set processing state
        PreferencesHelper.setToggleProcessing(this, true)
        PreferencesHelper.setToggleTimeout(this, currentTime)

        val hasAccessibilityPermission = PermissionsHelper.getAndAskAccessibilityPermission(this, false)

        if (!hasAccessibilityPermission) {
            Toast.makeText(this, "Accessibility permission required to activate protection", Toast.LENGTH_LONG).show()
            PermissionsHelper.getAndAskAccessibilityPermission(this, true)
            resetToggleState()
            return
        }

        val currentlyEnabled = PreferencesHelper.isProtectionEnabled(this)

        if (currentlyEnabled) {
            // Deactivating protection - requires password
            handleProtectionDeactivation()
        } else {
            // Activating protection - check if password is set, if not set it up
            if (!PreferencesHelper.isPasswordSet(this)) {
                setupPasswordForFirstTime()
            } else {
                activateProtection()
            }
        }
    }

    private fun resetToggleState() {
        PreferencesHelper.setToggleProcessing(this, false)
        PreferencesHelper.setToggleTimeout(this, 0L)
    }

    private fun handleProtectionDeactivation() {
        if (!PreferencesHelper.isPasswordSet(this)) {
            // No password set somehow - this is a security vulnerability
            // Log the incident and require password setup before allowing deactivation
            Toast.makeText(this, "Security error: Password not found. Please contact support.", Toast.LENGTH_LONG).show()
            resetToggleState()
            return
        }

        val passwordDialog = PasswordDialog(this)
        passwordDialog.showPasswordDialog(object : PasswordDialog.PasswordCallback {
            override fun onPasswordCorrect() {
                deactivateProtection()
            }

            override fun onPasswordCancelled() {
                // Do nothing - protection stays active
                resetToggleState()
            }
        })
    }

    private fun setupPasswordForFirstTime() {
        val passwordDialog = PasswordDialog(this)
        passwordDialog.showPasswordSetupDialog(object : PasswordDialog.PasswordSetupCallback {
            override fun onPasswordSet(password: String) {
                // Store the password
                val salt = SecurityHelper.generateSalt()
                val hash = SecurityHelper.hashPassword(password, salt)
                PreferencesHelper.setPasswordHash(this@MainActivity, hash)
                PreferencesHelper.setPasswordSalt(this@MainActivity, salt)

                Toast.makeText(this@MainActivity, getString(R.string.password_setup_success), Toast.LENGTH_LONG).show()

                // Now activate protection
                activateProtection()
            }

            override fun onPasswordCancelled() {
                // Don't activate protection if password setup was cancelled
                resetToggleState()
            }
        })
    }

    private fun activateProtection() {
        PreferencesHelper.setProtectionEnabled(this, true)

        // Start foreground service when protection is activated
        try {
            val foregroundServiceIntent = Intent()
                .setComponent(ComponentName(this, "com.ankit.blocker.services.BlockerForegroundService"))
                .setAction(ServiceBinder.ACTION_START_BLOCKER_SERVICE)
            startForegroundService(foregroundServiceIntent)
            Toast.makeText(this, getString(R.string.protection_activated), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start protection service: ${e.message}", Toast.LENGTH_LONG).show()
        }

        updatePermissionStatus()
        resetToggleState()
    }

    private fun deactivateProtection() {
        PreferencesHelper.setProtectionEnabled(this, false)

        // Stop foreground service when protection is deactivated
        try {
            val stopServiceIntent = Intent()
                .setComponent(ComponentName(this, "com.ankit.blocker.services.BlockerForegroundService"))
                .setAction("STOP_SERVICE")
            startService(stopServiceIntent)
            Toast.makeText(this, getString(R.string.protection_deactivated), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Protection deactivated, but service stop failed: ${e.message}", Toast.LENGTH_LONG).show()
        }

        updatePermissionStatus()
        resetToggleState()
    }


    private fun updatePermissionStatus() {
        updatePermissionStatusIfChanged()
    }

    /**
     * Updates permission status only if there are actual changes.
     * Returns true if changes were detected and UI was updated.
     * THREAD-SAFE: Synchronized to prevent race conditions.
     */
    @Synchronized
    internal fun updatePermissionStatusIfChanged(): Boolean {
        val currentStatus = PermissionStatus(
            hasAdmin = PermissionsHelper.getAndAskAdminPermission(this, false),
            hasAccessibility = PermissionsHelper.getAndAskAccessibilityPermission(this, false),
            hasOverlay = PermissionsHelper.hasOverlayPermission(this),
            isProtectionEnabled = PreferencesHelper.isProtectionEnabled(this),
            isProtectionActive = false // Will be set below
        ).let { it.copy(isProtectionActive = it.hasAccessibility && it.isProtectionEnabled) }

        // Check if status has actually changed
        val hasChanges = lastPermissionStatus == null || lastPermissionStatus != currentStatus

        if (hasChanges) {
            lastPermissionStatus = currentStatus
            updateStatusTexts(currentStatus)
            updateProtectionToggle(currentStatus)
            updatePermissionButtons(currentStatus)
        }

        return hasChanges
    }

    private fun updateStatusTexts(status: PermissionStatus) {
        adminStatusText.text = getString(
            if (status.hasAdmin) R.string.admin_permission_enabled
            else R.string.admin_permission_disabled
        )

        accessibilityStatusText.text = getString(
            if (status.hasAccessibility) R.string.accessibility_permission_enabled
            else R.string.accessibility_permission_disabled
        )

        overlayStatusText.text = if (status.hasOverlay) {
            "Display Over Other Apps: Enabled"
        } else {
            "Display Over Other Apps: Disabled"
        }

        protectionStatusText.text = getString(
            if (status.isProtectionActive) R.string.settings_protection_enabled
            else R.string.settings_protection_disabled
        )

        protectionStatusText.setTextColor(
            if (status.isProtectionActive) 0xFF4CAF50.toInt() else 0xFFFF6B35.toInt()
        )
    }

    private fun updateProtectionToggle(status: PermissionStatus) {
        protectionToggleButton.text = getString(
            if (status.isProtectionEnabled) R.string.deactivate_protection
            else R.string.activate_protection
        )

        protectionToggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (status.isProtectionEnabled) 0xFFF44336.toInt() else 0xFF4CAF50.toInt()
        )

        protectionToggleButton.isEnabled = status.hasAccessibility
    }

    private fun updatePermissionButtons(status: PermissionStatus) {
        enableAdminButton.isEnabled = !status.hasAdmin
        enableAccessibilityButton.isEnabled = !status.hasAccessibility
        enableOverlayButton.isEnabled = !status.hasOverlay

        enableAdminButton.text = if (status.hasAdmin) {
            "Device Admin: Enabled"
        } else {
            getString(R.string.enable_admin_permission)
        }

        enableAccessibilityButton.text = if (status.hasAccessibility) {
            "Accessibility: Enabled"
        } else {
            getString(R.string.enable_accessibility_permission)
        }
        
        enableOverlayButton.text = if (status.hasOverlay) {
            "Overlay: Enabled"
        } else {
            "Enable Overlay Permission"
        }
    }

    override fun onResume() {
        super.onResume()
        // User came back to the app - they might have changed permissions in Settings
        triggerActiveMode()
        
        // OPTIMIZED: Use lifecycle-aware coroutine with repeatOnLifecycle (replaces deprecated launchWhenResumed)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    updatePermissionStatusIfChanged()
                    delay(2000L) // Check every 2 seconds
                }
            }
        }
        // Coroutine automatically cancels when activity pauses/destroys!
    }

    override fun onPause() {
        super.onPause()
        // REMOVED: Handler cleanup (lifecycle coroutines auto-cancel)
        isActivelyChanging = false
    }

   override fun onDestroy() {
        super.onDestroy()
        // REMOVED: Handler cleanup (lifecycle coroutines auto-cancel)
        lastPermissionStatus = null
    }
}