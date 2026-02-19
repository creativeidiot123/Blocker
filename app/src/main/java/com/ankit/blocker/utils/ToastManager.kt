package com.ankit.blocker.utils

import android.content.Context
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Centralized Toast manager that prevents spam and provides rate limiting.
 * OPTIMIZATION: Tier 3 - eliminates toast queue overflow from rapid user actions.
 */
object ToastManager {
    private var lastToast: WeakReference<Toast>? = null
    private var lastToastTime = 0L
    private val MIN_TOAST_INTERVAL_MS = 1500L
    
    /**
     * Shows a toast, cancelling any previous toast.
     * Rate limited to max 1 toast per 3 seconds.
     */
    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        val currentTime = System.currentTimeMillis()
        
        // Rate limiting: Skip if too soon after last toast
        if (currentTime - lastToastTime < MIN_TOAST_INTERVAL_MS) {
            return
        }
        
        // Cancel previous toast to prevent queueing
        lastToast?.get()?.cancel()
        
        // Show new toast
        val toast = Toast.makeText(context, message, duration)
        toast.show()
        
        lastToast = WeakReference(toast)
        lastToastTime = currentTime
    }
    
}
