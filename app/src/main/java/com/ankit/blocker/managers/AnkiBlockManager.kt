package com.ankit.blocker.managers

import android.content.Context
import com.ankit.blocker.helpers.AnkiHelper
import com.ankit.blocker.utils.Logger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AnkiBlockManager(private val context: Context) {

    companion object {
        private const val TAG = "AnkiBlockManager"
        // REDUCED from 5000ms to 2000ms to cut unblock delay after completing reviews
        private const val CACHE_DURATION_MS = 2000L
        // On failure, retry sooner but still throttle to avoid IPC spam
        private const val FAILURE_RETRY_DELAY_MS = 1000L
    }

    // FIXED: Use atomic types to prevent data races from concurrent coroutine access
    private val cachedDueCards = AtomicInteger(0)
    private val lastCheckTime = AtomicLong(0L)

    /**
     * Checks if there are Anki cards due.
     * Uses caching to minimize IPC calls.
     *
     * @return true if due cards > 0
     */
    suspend fun areCardsDue(): Boolean {
        refreshDueCardsCacheIfNeeded()
        val count = cachedDueCards.get()
        if (count > 0) {
            Logger.d(TAG, "Blocking logic: $count Anki cards are due (from cache)")
            return true
        }
        return false
    }

    /**
     * Forces the cache to expire so the next [areCardsDue] call performs a fresh IPC query.
     * Call this when the user returns from AnkiDroid to unblock apps immediately.
     */
    fun invalidateCache() {
        lastCheckTime.set(0L)
        Logger.d(TAG, "Cache invalidated – next check will query AnkiDroid fresh")
    }

    private suspend fun refreshDueCardsCacheIfNeeded() {
        val currentTime = System.currentTimeMillis()
        // AtomicLong read is single-instruction on all architectures – safe without locking
        if (currentTime - lastCheckTime.get() <= CACHE_DURATION_MS) return

        Logger.d(TAG, "Cache expired, checking Anki due cards...")

        if (AnkiHelper.isAnkiDroidInstalled(context) && AnkiHelper.hasAnkiPermission(context)) {
            try {
                val count = AnkiHelper.getDueCardsCount(context)
                if (count >= 0) {
                    cachedDueCards.set(count)
                    lastCheckTime.set(currentTime)
                    Logger.d(TAG, "Due cards updated: $count")
                } else {
                    // FIXED: Update lastCheckTime even on failure to prevent IPC hammering.
                    // Use a shorter retry window (FAILURE_RETRY_DELAY_MS) so we retry soon
                    // without spamming the ContentProvider every single call.
                    lastCheckTime.set(currentTime - CACHE_DURATION_MS + FAILURE_RETRY_DELAY_MS)
                    Logger.w(TAG, "Failed to read due cards, using cached value: ${cachedDueCards.get()}. Will retry in ${FAILURE_RETRY_DELAY_MS}ms")
                }
            } catch (e: Exception) {
                lastCheckTime.set(currentTime - CACHE_DURATION_MS + FAILURE_RETRY_DELAY_MS)
                Logger.e(TAG, "Exception checking due cards", e)
            }
        } else {
            cachedDueCards.set(0)
            lastCheckTime.set(currentTime)
        }
    }
}
