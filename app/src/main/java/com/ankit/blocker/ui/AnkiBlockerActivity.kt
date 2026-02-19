package com.ankit.blocker.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.ankit.blocker.R
import com.ankit.blocker.helpers.AnkiHelper
import com.ankit.blocker.utils.Logger
import com.ankit.blocker.utils.PasswordDialog
import com.ankit.blocker.utils.PreferencesHelper
import com.ankit.blocker.utils.SecurityHelper
import com.google.android.material.materialswitch.MaterialSwitch

class AnkiBlockerActivity : AppCompatActivity() {

    private lateinit var enableSwitch: MaterialSwitch
    private lateinit var ankiStatusText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var dueCardsText: TextView
    private lateinit var grantPermissionButton: android.widget.Button
    private lateinit var selectAppsButton: android.widget.Button

    companion object {
        private const val TAG = "AnkiBlockerActivity"
    }

    // FIXED: Tracks an in-flight due-card query so the previous one is cancelled before launching
    // a new one. Prevents multiple coroutines racing to write to the same TextView.
    private var dueCardsJob: Job? = null

    // Guards against the switch listener firing when we set isChecked programmatically.
    private var programmaticChange = false

    // Modern permission launcher – replaces deprecated requestPermissions / onRequestPermissionsResult
    private val requestAnkiPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
            updateUI()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anki_blocker)
        title = "Anki Blocker Settings"

        enableSwitch = findViewById(R.id.enableSwitch)
        ankiStatusText = findViewById(R.id.ankiStatusText)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        dueCardsText = findViewById(R.id.dueCardsText)
        grantPermissionButton = findViewById(R.id.grantPermissionButton)
        selectAppsButton = findViewById(R.id.selectAppsButton)

        setupListeners()
    }

    private fun setupListeners() {
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (programmaticChange) return@setOnCheckedChangeListener

            if (isChecked) {
                // Enabling
                if (!PreferencesHelper.isPasswordSet(this)) {
                    // No password set - require setup
                    showPasswordSetupDialog()
                } else {
                    // Password already set - enable directly
                    PreferencesHelper.setAnkiBlockerEnabled(this, true)
                    updateUI()
                }
            } else {
                // Disabling - require password
                if (PreferencesHelper.isPasswordSet(this)) {
                    showPasswordDialog()
                } else {
                    // Inconsistent state (enabled but no password) - allow disable but warn
                    PreferencesHelper.setAnkiBlockerEnabled(this, false)
                    updateUI()
                }
            }
        }

        grantPermissionButton.setOnClickListener {
            Logger.d(TAG, "Requesting Anki permission")
            requestAnkiPermission.launch(AnkiHelper.ANKI_PERMISSION)
        }

        selectAppsButton.setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }
    }

    private fun showPasswordDialog() {
        val passwordDialog = PasswordDialog(this)
        passwordDialog.showPasswordDialog(object : PasswordDialog.PasswordCallback {
            override fun onPasswordCorrect() {
                PreferencesHelper.setAnkiBlockerEnabled(this@AnkiBlockerActivity, false)
                updateUI()
            }

            override fun onPasswordCancelled() {
                // Revert switch to ON without triggering the listener
                programmaticChange = true
                enableSwitch.isChecked = true
                programmaticChange = false
            }
        })
    }

    private fun showPasswordSetupDialog() {
        val passwordDialog = PasswordDialog(this)
        passwordDialog.showPasswordSetupDialog(object : PasswordDialog.PasswordSetupCallback {
            override fun onPasswordSet(password: String) {
                // FIXED: Write hash + salt in a single atomic apply() to avoid partial writes
                val salt = SecurityHelper.generateSalt()
                val hash = SecurityHelper.hashPassword(password, salt)
                PreferencesHelper.setPasswordHashAndSalt(this@AnkiBlockerActivity, hash, salt)

                Toast.makeText(this@AnkiBlockerActivity, "Password set! Blocker enabled.", Toast.LENGTH_SHORT).show()

                PreferencesHelper.setAnkiBlockerEnabled(this@AnkiBlockerActivity, true)
                updateUI()
            }

            override fun onPasswordCancelled() {
                // Revert switch to OFF without triggering the listener
                programmaticChange = true
                enableSwitch.isChecked = false
                programmaticChange = false
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        // FIXED: Guard the programmatic switch assignment so the listener doesn't react to it.
        // Without this, setting isChecked triggers the listener → which calls updateUI() again → loop.
        programmaticChange = true
        enableSwitch.isChecked = PreferencesHelper.isAnkiBlockerEnabled(this)
        programmaticChange = false

        // Check if AnkiDroid is installed
        val isAnkiInstalled = AnkiHelper.isAnkiDroidInstalled(this)
        if (isAnkiInstalled) {
            ankiStatusText.text = "AnkiDroid: Installed"
            ankiStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            ankiStatusText.text = "AnkiDroid: Not Installed!"
            ankiStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
            permissionStatusText.text = "Cannot proceed without AnkiDroid"
            dueCardsText.text = ""
            grantPermissionButton.visibility = android.view.View.GONE
            return
        }

        // Check Permission
        val hasPermission = AnkiHelper.hasAnkiPermission(this)
        if (hasPermission) {
            permissionStatusText.text = "Permission: Granted"
            permissionStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
            grantPermissionButton.visibility = android.view.View.GONE

            // FIXED: Cancel any previous in-flight coroutine before launching a new one.
            // Previously, rapid onResume calls (e.g., returning from AnkiDroid) could spawn
            // multiple coroutines that all race to update dueCardsText.
            dueCardsJob?.cancel()
            dueCardsJob = lifecycleScope.launch {
                try {
                    val dueCount = AnkiHelper.getDueCardsCount(this@AnkiBlockerActivity)
                    if (dueCount >= 0) {
                        dueCardsText.text = "Due Cards: $dueCount"
                        dueCardsText.setTextColor(getColor(android.R.color.holo_green_dark))
                    } else {
                        dueCardsText.text = "Error reading cards (Check AnkiDroidDB)"
                        dueCardsText.setTextColor(getColor(android.R.color.holo_orange_dark))
                    }
                } catch (e: Exception) {
                    dueCardsText.text = "Error: ${e.message}"
                    Logger.e(TAG, "Error checking cards", e)
                }
            }

        } else {
            permissionStatusText.text = "Permission: Not Granted"
            permissionStatusText.setTextColor(getColor(android.R.color.holo_red_dark))
            grantPermissionButton.visibility = android.view.View.VISIBLE
            dueCardsText.text = "Grant permission to see due cards"
        }
    }
}
