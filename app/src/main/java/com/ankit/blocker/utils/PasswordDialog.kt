package com.ankit.blocker.utils

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.ankit.blocker.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.lang.ref.WeakReference

/**
 * Countdown runnable that uses WeakReference to prevent memory leaks
 */
private class LockoutCountdownRunnable(
    dialog: AlertDialog,
    context: Context,
    callback: PasswordDialog.PasswordCallback,
    passwordDialog: PasswordDialog
) : Runnable {
    private val dialogRef = WeakReference(dialog)
    private val contextRef = WeakReference(context)
    private val callbackRef = WeakReference(callback)
    private val passwordDialogRef = WeakReference(passwordDialog)

    override fun run() {
        val dialog = dialogRef.get()
        val context = contextRef.get()
        val callback = callbackRef.get()
        val passwordDialog = passwordDialogRef.get()

        if (dialog == null || context == null || callback == null || passwordDialog == null) {
            // References cleared, cleanup and stop execution
            return
        }

        if (!dialog.isShowing) {
            // Dialog dismissed, cleanup and stop execution
            passwordDialog.cleanupLockoutCountdown()
            return
        }

        val remainingTime = SecurityHelper.getRemainingLockoutTime(context)

        if (remainingTime <= 0) {
            // Countdown completed - cleanup before proceeding
            passwordDialog.cleanupLockoutCountdown()
            dialog.dismiss()
            passwordDialog.showPasswordDialog(callback) // Show password dialog again
        } else {
            val formattedTime = SecurityHelper.formatLockoutTime(remainingTime)
            dialog.setMessage(context.getString(R.string.password_locked_out, formattedTime))
            passwordDialog.lockoutHandler?.postDelayed(this, 1000) // Update every second
        }
    }
}

/**
 * PasswordDialog handles password entry dialogs for protection deactivation
 * and initial password setup with security features like lockout protection.
 * Uses WeakReferences to prevent memory leaks.
 */
class PasswordDialog(context: Context) {

    interface PasswordCallback {
        fun onPasswordCorrect()
        fun onPasswordCancelled()
    }

    interface PasswordSetupCallback {
        fun onPasswordSet(password: String)
        fun onPasswordCancelled()
    }

    private val contextRef = WeakReference(context)
    private var dialogRef: WeakReference<AlertDialog>? = null
    internal var lockoutHandler: Handler? = null
    private var lockoutRunnable: LockoutCountdownRunnable? = null

    /**
     * Shows a password entry dialog for deactivating protection.
     *
     * @param callback Callback for handling password verification results.
     */
    fun showPasswordDialog(callback: PasswordCallback) {
        val context = contextRef.get() ?: return

        // Check if locked out
        if (SecurityHelper.isLockedOut(context)) {
            showLockoutMessage(callback)
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_password, null)

        val titleText = view.findViewById<TextView>(R.id.dialogTitle)
        val messageText = view.findViewById<TextView>(R.id.dialogMessage)
        val passwordInputLayout = view.findViewById<TextInputLayout>(R.id.passwordInputLayout)
        val passwordEditText = view.findViewById<TextInputEditText>(R.id.passwordEditText)
        val errorText = view.findViewById<TextView>(R.id.errorText)
        val attemptCounterText = view.findViewById<TextView>(R.id.attemptCounterText)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)
        val confirmButton = view.findViewById<Button>(R.id.confirmButton)

        // Set up dialog content
        titleText.text = context.getString(R.string.password_dialog_title)
        messageText.text = context.getString(R.string.password_dialog_message)

        // Show attempt counter if there are failed attempts
        val failedAttempts = PreferencesHelper.getFailedPasswordAttempts(context)
        if (failedAttempts > 0) {
            val remainingAttempts = maxOf(0, 5 - failedAttempts)
            attemptCounterText.text = context.getString(R.string.password_attempts_remaining, remainingAttempts)
            attemptCounterText.visibility = View.VISIBLE
        }

        // Clear error when user starts typing
        passwordEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                errorText.visibility = View.GONE
                passwordInputLayout.error = null
            }
        })

        val newDialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(false)
            .create()

        dialogRef = WeakReference(newDialog)

        // Button click handlers
        cancelButton.setOnClickListener {
            dismiss()
            callback.onPasswordCancelled()
        }

        confirmButton.setOnClickListener {
            val enteredPassword = passwordEditText.text?.toString() ?: ""
            if (validatePassword(enteredPassword, errorText, passwordInputLayout)) {
                SecurityHelper.clearFailedAttempts(context)
                dismiss()
                callback.onPasswordCorrect()
            } else {
                SecurityHelper.recordFailedAttempt(context)

                // Check if now locked out
                if (SecurityHelper.isLockedOut(context)) {
                    dismiss()
                    showLockoutMessage(callback)
                } else {
                    // Update attempt counter
                    val newFailedAttempts = PreferencesHelper.getFailedPasswordAttempts(context)
                    val remainingAttempts = maxOf(0, 5 - newFailedAttempts)
                    attemptCounterText.text = context.getString(R.string.password_attempts_remaining, remainingAttempts)
                    attemptCounterText.visibility = View.VISIBLE
                }
            }
        }

        newDialog.show()
    }

    /**
     * Shows a password setup dialog for first-time password creation.
     *
     * @param callback Callback for handling password setup results.
     */
    fun showPasswordSetupDialog(callback: PasswordSetupCallback) {
        val context = contextRef.get() ?: return

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_password_setup, null)

        val titleText = view.findViewById<TextView>(R.id.dialogTitle)
        val messageText = view.findViewById<TextView>(R.id.dialogMessage)
        val passwordInputLayout = view.findViewById<TextInputLayout>(R.id.passwordInputLayout)
        val passwordEditText = view.findViewById<TextInputEditText>(R.id.passwordEditText)
        val confirmInputLayout = view.findViewById<TextInputLayout>(R.id.confirmInputLayout)
        val confirmEditText = view.findViewById<TextInputEditText>(R.id.confirmEditText)
        val errorText = view.findViewById<TextView>(R.id.errorText)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)
        val confirmButton = view.findViewById<Button>(R.id.confirmButton)

        titleText.text = context.getString(R.string.password_setup_title)
        messageText.text = context.getString(R.string.password_setup_message)

        // Clear errors when user types
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                errorText.visibility = View.GONE
                passwordInputLayout.error = null
                confirmInputLayout.error = null
            }
        }
        passwordEditText.addTextChangedListener(textWatcher)
        confirmEditText.addTextChangedListener(textWatcher)

        val newDialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(false)
            .create()

        dialogRef = WeakReference(newDialog)

        cancelButton.setOnClickListener {
            dismiss()
            callback.onPasswordCancelled()
        }

        confirmButton.setOnClickListener {
            val password = passwordEditText.text?.toString() ?: ""
            val confirmPassword = confirmEditText.text?.toString() ?: ""

            if (validatePasswordSetup(password, confirmPassword, errorText, passwordInputLayout, confirmInputLayout)) {
                dismiss()
                callback.onPasswordSet(password)
            }
        }

        newDialog.show()
    }

    private fun validatePassword(password: String, errorText: TextView, inputLayout: TextInputLayout): Boolean {
        val context = contextRef.get() ?: return false
        val validationError = SecurityHelper.getPasswordValidationError(password)
        if (validationError != null) {
            showError(errorText, inputLayout, validationError)
            return false
        }

        val storedHash = PreferencesHelper.getPasswordHash(context)
        val salt = PreferencesHelper.getPasswordSalt(context)

        if (storedHash == null || salt == null) {
            showError(errorText, inputLayout, "Password not set up")
            return false
        }

        if (!SecurityHelper.validatePassword(password, storedHash, salt)) {
            showError(errorText, inputLayout, context.getString(R.string.password_incorrect))
            return false
        }

        return true
    }

    private fun validatePasswordSetup(
        password: String,
        confirmPassword: String,
        errorText: TextView,
        passwordLayout: TextInputLayout,
        confirmLayout: TextInputLayout
    ): Boolean {
        val context = contextRef.get() ?: return false

        val validationError = SecurityHelper.getPasswordValidationError(password)
        if (validationError != null) {
            showError(errorText, passwordLayout, validationError)
            return false
        }

        if (password != confirmPassword) {
            showError(errorText, confirmLayout, context.getString(R.string.passwords_dont_match))
            return false
        }

        return true
    }

    private fun showError(errorText: TextView, inputLayout: TextInputLayout, message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
        inputLayout.error = " " // Show error state without text (we use errorText instead)
    }

    private fun showLockoutMessage(callback: PasswordCallback) {
        val context = contextRef.get() ?: return

        val failedAttempts = PreferencesHelper.getFailedPasswordAttempts(context)

        if (failedAttempts >= 10) {
            // Permanent lockout
            val permanentLockoutDialog = AlertDialog.Builder(context)
                .setTitle("Account Locked")
                .setMessage(context.getString(R.string.password_permanent_lockout))
                .setPositiveButton("OK") { _, _ -> callback.onPasswordCancelled() }
                .setCancelable(false)
                .create()

            dialogRef = WeakReference(permanentLockoutDialog)
            permanentLockoutDialog.show()
        } else {
            // Temporary lockout
            val remainingTime = SecurityHelper.getRemainingLockoutTime(context)
            val formattedTime = SecurityHelper.formatLockoutTime(remainingTime)

            val lockoutDialog = AlertDialog.Builder(context)
                .setTitle("Too Many Attempts")
                .setMessage(context.getString(R.string.password_locked_out, formattedTime))
                .setNegativeButton("Cancel") { _, _ -> callback.onPasswordCancelled() }
                .setCancelable(false)
                .create()

            dialogRef = WeakReference(lockoutDialog)
            lockoutDialog.show()

            // Update the countdown
            startLockoutCountdown(lockoutDialog, callback)
        }
    }

    private fun startLockoutCountdown(lockoutDialog: AlertDialog, callback: PasswordCallback) {
        val context = contextRef.get() ?: return

        // Clean up any existing countdown
        cleanupLockoutCountdown()

        lockoutHandler = Handler(Looper.getMainLooper())
        lockoutRunnable = LockoutCountdownRunnable(lockoutDialog, context, callback, this)

        lockoutHandler?.postDelayed(lockoutRunnable!!, 1000)
    }

    internal fun cleanupLockoutCountdown() {
        lockoutRunnable?.let { runnable ->
            lockoutHandler?.removeCallbacks(runnable)
        }
        lockoutRunnable = null
        lockoutHandler = null
    }


    fun dismiss() {
        // Safe cleanup of handler callbacks
        cleanupLockoutCountdown()

        // Dismiss dialog through weak reference
        dialogRef?.get()?.let { dialog ->
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
        dialogRef = null
    }
}