package com.ankit.blocker.utils

import android.util.Log
import com.ankit.blocker.BuildConfig

/**
 * Logging utility optimized for performance.
 * Uses inline functions to prevent string allocation when logging is disabled.
 * Automatically strips debug logs in release builds (R8).
 */
object Logger {
    const val DEFAULT_TAG = "Blocker"
    
    /**
     * Debug logging - compiled out in release.
     */
    inline fun d(tag: String = DEFAULT_TAG, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message())
        }
    }
    
    // Overload for string (backwards compatibility, but inline preferred)
    fun d(tag: String = DEFAULT_TAG, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    /**
     * Warning logging - always kept
     */
    fun w(tag: String = DEFAULT_TAG, message: String) {
        Log.w(tag, message)
    }

    /**
     * Error logging - always kept
     */
    fun e(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    /**
     * Info logging - always kept
     */
    fun i(tag: String = DEFAULT_TAG, message: String) {
        Log.i(tag, message)
    }

    /**
     * Verbose logging - compiled out in release.
     */
    inline fun v(tag: String = DEFAULT_TAG, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, message())
        }
    }

    /**
     * WTF (What a Terrible Failure) logging - for states that should never happen.
     * Always kept, triggers runtime signal.
     */
    fun wtf(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.wtf(tag, message, throwable)
        } else {
            Log.wtf(tag, message)
        }
    }
}