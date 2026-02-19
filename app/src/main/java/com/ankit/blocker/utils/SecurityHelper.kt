package com.ankit.blocker.utils

import android.content.Context
import java.security.SecureRandom
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * SecurityHelper provides utility methods for password hashing, validation,
 * and security-related operations for the Blocker application.
 */
object SecurityHelper {
    private const val TAG = "Blocker.SecurityHelper"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SALT_LENGTH = 32
    private const val KEY_LENGTH = 256
    private const val ITERATIONS = 100000

    /**
     * Generates a random salt for password hashing.
     *
     * @return A random salt as base64 encoded string.
     */
    fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }

    /**
     * Hashes a password with the given salt using PBKDF2.
     *
     * @param password The plain text password to hash.
     * @param salt The base64 encoded salt to use for hashing.
     * @return The hashed password as a base64 encoded string.
     */
    fun hashPassword(password: String, salt: String): String {
        return try {
            val saltBytes = Base64.getDecoder().decode(salt)
            val spec = PBEKeySpec(password.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH)
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val hash = factory.generateSecret(spec).encoded
            Base64.getEncoder().encodeToString(hash)
        } catch (e: Exception) {
            // If PBKDF2 is not available, throw an exception - this should not happen on modern Android
            throw RuntimeException("PBKDF2 hashing failed - device may be compromised", e)
        }
    }

    /**
     * Validates a password against a stored hash and salt using constant-time comparison.
     *
     * @param enteredPassword The password entered by the user.
     * @param storedHash The stored password hash.
     * @param salt The salt used for the stored hash.
     * @return True if the password is valid, false otherwise.
     */
    fun validatePassword(enteredPassword: String, storedHash: String, salt: String): Boolean {
        val enteredHash = hashPassword(enteredPassword, salt)
        return constantTimeEquals(enteredHash, storedHash)
    }

    /**
     * Performs constant-time string comparison to prevent timing attacks.
     *
     * @param a First string to compare.
     * @param b Second string to compare.
     * @return True if strings are equal, false otherwise.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }

        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * Checks if password meets minimum requirements.
     *
     * @param password The password to validate.
     * @return True if password meets requirements, false otherwise.
     */
    fun isPasswordValid(password: String): Boolean {
        return password.length >= 4 // Minimum 4 characters
    }

    /**
     * Gets the validation error message for a password.
     *
     * @param password The password to validate.
     * @return Error message if invalid, null if valid.
     */
    fun getPasswordValidationError(password: String): String? {
        return when {
            password.isEmpty() -> "Password cannot be empty"
            password.length < 4 -> "Password must be at least 4 characters"
            else -> null
        }
    }

    /**
     * Checks if the user should be locked out due to too many failed attempts.
     *
     * @param context The application context.
     * @return True if user should be locked out, false otherwise.
     */
    fun isLockedOut(context: Context): Boolean {
        val failedAttempts = PreferencesHelper.getFailedPasswordAttempts(context)
        val lastAttemptTime = PreferencesHelper.getLastFailedAttemptTime(context)
        val currentTime = System.currentTimeMillis()

        return when {
            failedAttempts >= 10 -> {
                // Permanent lockout after 10 attempts - requires reset
                true
            }
            failedAttempts >= 5 -> {
                // 5-minute lockout after 5 attempts
                currentTime - lastAttemptTime < 5 * 60 * 1000
            }
            else -> false
        }
    }

    /**
     * Gets the remaining lockout time in milliseconds.
     *
     * @param context The application context.
     * @return Remaining lockout time in milliseconds, or 0 if not locked out.
     */
    fun getRemainingLockoutTime(context: Context): Long {
        if (!isLockedOut(context)) return 0

        val lastAttemptTime = PreferencesHelper.getLastFailedAttemptTime(context)
        val currentTime = System.currentTimeMillis()
        val lockoutDuration = 5 * 60 * 1000 // 5 minutes

        return maxOf(0, lockoutDuration - (currentTime - lastAttemptTime))
    }

    /**
     * Records a failed password attempt.
     *
     * @param context The application context.
     */
    fun recordFailedAttempt(context: Context) {
        val currentAttempts = PreferencesHelper.getFailedPasswordAttempts(context)
        PreferencesHelper.setFailedPasswordAttempts(context, currentAttempts + 1)
        PreferencesHelper.setLastFailedAttemptTime(context, System.currentTimeMillis())
    }

    /**
     * Clears failed password attempts (called on successful authentication).
     *
     * @param context The application context.
     */
    fun clearFailedAttempts(context: Context) {
        PreferencesHelper.setFailedPasswordAttempts(context, 0)
        PreferencesHelper.setLastFailedAttemptTime(context, 0)
    }

    /**
     * Formats remaining lockout time for display.
     *
     * @param remainingMs Remaining time in milliseconds.
     * @return Formatted time string (e.g., "4:32" for 4 minutes 32 seconds).
     */
    fun formatLockoutTime(remainingMs: Long): String {
        val totalSeconds = remainingMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}