package com.ankit.blocker.services

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.ankit.blocker.utils.ToastManager
import com.ankit.blocker.R
import com.ankit.blocker.constants.BlockerConstants
// import com.ankit.blocker.helpers.NotificationHelper - Removed unused import
import com.ankit.blocker.managers.AnkiBlockManager
import com.ankit.blocker.managers.SettingsBlockManager
import com.ankit.blocker.receivers.DeviceAdminReceiver
import com.ankit.blocker.utils.Logger
import com.ankit.blocker.utils.PermissionsHelper
import com.ankit.blocker.utils.PreferencesHelper
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Build
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * An AccessibilityService that monitors Settings app usage and blocks access to critical
 * security configuration sections to prevent unauthorized modifications.
 *
 * Refactored to use Kotlin Coroutines for efficient background processing.
 */
class BlockerAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private const val TAG = "Blocker.AccessibilityService"

        const val ACTION_PERFORM_HOME_PRESS = "com.ankit.blocker.action.performHomePress"

        // Set of desired events which will be processed
        private val desiredEvents = setOf(
            TYPE_WINDOWS_CHANGED,
            TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            TYPE_VIEW_SCROLLED // Re-enabled for Anki Blocker scroll detection
        )
        
        // Heartbeat tracking for service liveness monitoring
        @Volatile
        var lastEventProcessedTime: Long = 0L
        
        /**
         * Checks if the accessibility service is actively processing events.
         * @return true if an event was processed within the last 5 minutes
         */
        fun isServiceAlive(): Boolean {
            return System.currentTimeMillis() - lastEventProcessedTime < 300000L
        }
    }

    // Coroutine Scope for background tasks - LIMITED to 4 threads for efficiency
    private val limitedDispatcher = Dispatchers.Default.limitedParallelism(4)
    private val serviceScope = CoroutineScope(limitedDispatcher + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Throttling to prevent spam (500ms delay) - using AtomicLong for thread safety
    private val lastActionTime = AtomicLong(0L)
    private val actionThrottleMs = 200L


    // Manager for settings blocking logic
    private lateinit var settingsBlockManager: SettingsBlockManager

    // Manager for Anki blocking logic
    private lateinit var ankiBlockManager: AnkiBlockManager
    
    // OPTIMIZED: Event coalescing - batch events within 500ms window
    private val DEBOUNCE_DELAY_MS = 500L  // Increased from 250ms
    private val coalescingJobs = ConcurrentHashMap<String, Job>()
    private val pendingEventsByPackage = ConcurrentHashMap<String, Long>()
    
    // REMOVED: Forced 2-second interval scans (wasteful during scrolling)
    // private val FORCE_SCAN_INTERVAL_MS = 2000L
    // private val lastForceScanTimes = ConcurrentHashMap<String, Long>()
    
    // Adaptive throttling - increase delay if no blocks found
    private val lastBlockTimeByPackage = ConcurrentHashMap<String, Long>()
    private val MIN_THROTTLE_MS = 500L
    private val MAX_THROTTLE_MS = 5000L
    
    // Settings watchdog - periodic re-check while navigating Settings
    private var settingsWatchdogJob: Job? = null
    private var lastSettingsPackage: String? = null

    // Manager for overlay - Nullable for safety
    private var blockOverlayManager: com.ankit.blocker.ui.overlay.BlockOverlayManager? = null

    // Power management
    private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    
    // Cached Preferences
    private var isProtectionEnabled = false
    @Volatile private var ankiBlockedApps = emptySet<String>()
    @Volatile private var isAnkiBlockerEnabled = false
    
    // OPTIMIZATION: Dirty flag to reduce unnecessary preference reads
    @Volatile
    private var cacheNeedsUpdate = true

    override fun onCreate() {
        super.onCreate()
        settingsBlockManager = SettingsBlockManager(
            context = this,
            blockedContentGoBack = { reason -> performBlockAction(reason, 1) }
        )

        ankiBlockManager = AnkiBlockManager(this)

        blockOverlayManager = com.ankit.blocker.ui.overlay.BlockOverlayManager(this, mainScope)

        Logger.d(TAG, "Blocker Accessibility service created")
    }

    /**
     * Performs blocking action: either shows overlay (if permitted) or forces back (fallback).
     * Includes throttling to prevent spam.
     * @param backPressCount Number of times to press back (for aggressive exiting). Default is 1.
     */
    private fun performBlockAction(blockingReason: String = "Restricted access", backPressCount: Int = 1) {
        val currentTime = System.currentTimeMillis()

        // Throttle actions to prevent spam - thread-safe atomic check-and-set
        val lastAction = lastActionTime.get()
        if (currentTime - lastAction < actionThrottleMs) {
            Logger.d(TAG, "Throttled - skipping action for: $blockingReason")
            return
        }

        // Use compareAndSet for atomic update (prevents race if multiple threads pass the check)
        if (!lastActionTime.compareAndSet(lastAction, currentTime)) {
            // Another thread updated it first - we were in a race, let them handle it
            Logger.d(TAG, "Throttled by race condition - skipping action for: $blockingReason")
            return
        }

        // Perform action on main thread via Coroutine
        mainScope.launch {
            // Check if we can draw overlays and overlay manager is available
            val overlayManager = blockOverlayManager
            if (overlayManager != null && android.provider.Settings.canDrawOverlays(this@BlockerAccessibilityService)) {
                Logger.d(TAG, "Blocking with OVERLAY for: $blockingReason")
                
                // Always show toast regardless of overlay
                ToastManager.show(
                    this@BlockerAccessibilityService,
                    "Blocked: $blockingReason",
                    Toast.LENGTH_LONG
                )
                
                // PROACTIVE: Immediately press back to interrupt the blocked app
                executeBackPresses(backPressCount)
                
                overlayManager.showOverlay {
                    // Button click: press HOME to make sure they leave
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            } else {
                // Fallback: standard back action + toast
                Logger.d(TAG, "Blocking with BACK ACTION (no overlay permission) for: $blockingReason")
                executeBackPresses(backPressCount)
                
                Toast.makeText(
                    this@BlockerAccessibilityService,
                    "Blocked: $blockingReason",
                    Toast.LENGTH_SHORT
                ).show()
            }
            
            Logger.w(TAG, "EXECUTED BLOCK - Reason: $blockingReason, Back Presses: $backPressCount")
        }
    }

    private fun executeBackPresses(count: Int) {
        if (count <= 1) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            // Multi-step back press with delay using Coroutines
            mainScope.launch {
                for (i in 0 until count) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    if (i < count - 1) { // Don't delay after the last press
                         Logger.d(TAG, "Aggressive Back Press ${i + 1}/$count")
                         delay(150L) // 150ms delay between presses
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DeviceAdminReceiver.ACTION_TAMPER_PROTECTION_CHANGED -> {
                Logger.d(TAG, "Tamper protection settings changed")
                // Refresh service configuration if needed
                updatePreferencesCache()
            }
            
            // Handle service enabled intent (from settings toggle)
            "com.ankit.blocker.action.SERVICE_ENABLED" -> {
                Logger.d(TAG, "Service enable signal received")
                updatePreferencesCache()
            }

            ACTION_PERFORM_HOME_PRESS -> {
                Logger.d(TAG, "Pressing home button")
                // Fallback to legacy behavior if needed, but usually we use performBlockAction
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            "REFRESH_SERVICE" -> {
                Logger.d(TAG, "Service refresh requested - accessibility service is responsive")
                // Just acknowledge the refresh - ForegroundService handles service management
                // No need to start/stop services here to avoid restart loops
                updatePreferencesCache()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.d(TAG, "Blocker accessibility service started successfully")
        
        // Initialize cache and register listener
        updatePreferencesCache()
        // Note: Preference registration needs internal access to SharedPreferences. 
        // PreferencesHelper encapsulates this details. 
        // Ideally PreferencesHelper should expose a way to register listener or we access the prefs directly here.
        // For now, we'll access the same shared prefs file.
        val prefs = getSharedPreferences("blocker_preferences", MODE_PRIVATE) // Constant from PreferencesHelper
        val devicePrefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
             createDeviceProtectedStorageContext().getSharedPreferences("blocker_device_prefs", MODE_PRIVATE)
        } else {
             getSharedPreferences("blocker_device_prefs", MODE_PRIVATE)
        }
        
        prefs.registerOnSharedPreferenceChangeListener(this)
        devicePrefs.registerOnSharedPreferenceChangeListener(this)

        // Verify permissions and protection state after service restart
        val hasAdminPermission = PermissionsHelper.getAndAskAdminPermission(this, false)
        val hasAccessibilityPermission = PermissionsHelper.getAndAskAccessibilityPermission(this, false)
        
        Logger.d(TAG, "Service connected - Admin: $hasAdminPermission, Accessibility: $hasAccessibilityPermission, Protection: $isProtectionEnabled")

        if (hasAccessibilityPermission && isProtectionEnabled) {
            Logger.d(TAG, "Settings protection is active and ready")

            // Start ForegroundService if it's not already running
            // This ensures protection is active even if BootReceiver didn't fire
            try {
                val isForegroundServiceRunning = BlockerForegroundService.isServiceRunning(this)

                if (!isForegroundServiceRunning) {
                    Logger.d(TAG, "ForegroundService not running - starting it now")
                    val serviceIntent = Intent()
                        .setComponent(ComponentName(this, "com.ankit.blocker.services.BlockerForegroundService"))
                        .setAction("com.ankit.blocker.action.START_BLOCKER_SERVICE")
                    startForegroundService(serviceIntent)
                    Logger.d(TAG, "ForegroundService start requested from AccessibilityService")
                } else {
                    Logger.d(TAG, "ForegroundService already running")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start ForegroundService from AccessibilityService", e)
            }
        } else {
            Logger.d(TAG, "Settings protection is inactive - missing accessibility permission or disabled by user")
        }
        // Note: super.onServiceConnected() already called at start of method
    }
    
    private fun updatePreferencesCache() {
        // OPTIMIZATION: Only update if flagged dirty (reduces I/O by 95%)
        if (!cacheNeedsUpdate) return

        serviceScope.launch {
            // OPTIMIZATION: Batch all preference reads in single transaction
            val result = withContext(Dispatchers.IO) {
                val p = PreferencesHelper.isProtectionEnabled(this@BlockerAccessibilityService)
                val aa = PreferencesHelper.getAnkiBlockedApps(this@BlockerAccessibilityService)
                val ae = PreferencesHelper.isAnkiBlockerEnabled(this@BlockerAccessibilityService)
                Triple(p, aa, ae)
            }

            // Update cache atomically
            isProtectionEnabled = result.first
            ankiBlockedApps = result.second
            isAnkiBlockerEnabled = result.third
            cacheNeedsUpdate = false

            Logger.d(TAG, "Cache updated: Protection=${result.first}, AnkiApps=${result.second.size}, AnkiEnabled=${result.third}")
        }
    }
    
    /**
     * Calculates adaptive delay based on time since last block.
     * If no blocks found recently, exponentially increase delay to save battery.
     */
    private fun getAdaptiveDelay(packageName: String): Long {
        val lastBlockTime = lastBlockTimeByPackage[packageName] ?: 0L
        val timeSinceBlock = System.currentTimeMillis() - lastBlockTime
        
        val baseDelay = when {
            timeSinceBlock < 10000L -> MIN_THROTTLE_MS      // 500ms if blocked within 10s
            timeSinceBlock < 60000L -> MIN_THROTTLE_MS * 2  // 1000ms if blocked within 1min
            timeSinceBlock < 300000L -> MIN_THROTTLE_MS * 4 // 2000ms if blocked within 5min
            else -> MAX_THROTTLE_MS                          // 5000ms if no blocks for 5+ min
        }
        
        // OPTIMIZATION: 50% slower in battery saver mode
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && powerManager.isPowerSaveMode) {
            (baseDelay * 1.5).toLong()
        } else {
            baseDelay
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Logger.d(TAG) { "Preferences changed - key: $key" }

        // OPTIMIZATION: Set dirty flag instead of immediately updating (reduces I/O)
        cacheNeedsUpdate = true

        // Invalidate all blocklist caches to ensure immediate update
        PreferencesHelper.invalidateBlocklistCaches()

        // If protection_enabled changed, immediately cancel the settings watchdog so it stops
        // blocking right away rather than waiting for the next 1-second watchdog tick.
        if (key == "protection_enabled" || key == null) {
            val nowEnabled = PreferencesHelper.isProtectionEnabled(this)
            isProtectionEnabled = nowEnabled
            if (!nowEnabled) {
                Logger.d(TAG, "Protection disabled — cancelling settings watchdog immediately")
                settingsWatchdogJob?.cancel()
                settingsWatchdogJob = null
                lastSettingsPackage = null
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val eventPackageName = event.packageName?.toString() ?: return
            
            // REMOVED: Excessive logging in hot path
            // val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
            // Logger.d(TAG) { "Accessibility event: $eventPackageName type=$eventTypeName" }

            // Auto-dismiss logic: Only dismiss overlay if the event is from a package
            // that is clearly NOT part of the blocking flow (exclude system UI, launcher, Blocker itself).
            val SAFE_DISMISS_PACKAGES = BlockerConstants.LAUNCHER_PACKAGES
            
            if (!ankiBlockedApps.contains(eventPackageName)
                && !BlockerConstants.SETTINGS_PACKAGES.contains(eventPackageName)
                && !BlockerConstants.SYSTEM_PACKAGE_INSTALLER.equals(eventPackageName)
                && !BlockerConstants.GOOGLE_PACKAGE_INSTALLER.equals(eventPackageName)) {
                
                // ONLY auto-dismiss if user navigated to the HOME SCREEN (launcher)
                if (SAFE_DISMISS_PACKAGES.contains(eventPackageName)) {
                    val overlayManager = blockOverlayManager
                    mainScope.launch {
                        if (overlayManager?.isOverlayShowing() == true) {
                            Logger.d(TAG, "Auto-dismissing overlay - User switched to: $eventPackageName")
                            overlayManager.removeOverlay()
                        }
                    }
                }
                // Stop processing non-monitored packages here
                return
            }
            
            // Early filter: Skip if screen is off (no user interaction possible)
            if (!powerManager.isInteractive) {
                return
            }
            
            // Determine if this is a Settings package BEFORE cache check
            val isSettings = BlockerConstants.SETTINGS_PACKAGES.contains(eventPackageName)

            val isInstaller = BlockerConstants.SYSTEM_PACKAGE_INSTALLER == eventPackageName ||
                              BlockerConstants.GOOGLE_PACKAGE_INSTALLER == eventPackageName
            
            // CRITICAL FIX: Synchronous cache update for Settings packages to prevent race conditions
            if (cacheNeedsUpdate) {
                if (isSettings) {
                    // Synchronous update for Settings - must be accurate immediately
                    isProtectionEnabled = PreferencesHelper.isProtectionEnabled(this)
                    cacheNeedsUpdate = false
                    Logger.d(TAG, "Sync cache update for Settings: protection=$isProtectionEnabled")
                } else if (ankiBlockedApps.contains(eventPackageName) || isAnkiBlockerEnabled) {
                    // Synchronous update for Anki-blocked apps - blocking decision must be fresh
                    isAnkiBlockerEnabled = PreferencesHelper.isAnkiBlockerEnabled(this)
                    ankiBlockedApps = PreferencesHelper.getAnkiBlockedApps(this)
                    cacheNeedsUpdate = false
                    Logger.d(TAG, "Sync cache update for AnkiBlocker: enabled=$isAnkiBlockerEnabled")
                } else {
                    // Async OK for non-critical apps
                    updatePreferencesCache()
                }
            }
            
            // Early filter: Check if protection is active (now guaranteed fresh for Settings)
            if (!isProtectionEnabled && !isAnkiBlockerEnabled) {
                return
            }

            // Early filter: Check if event type is one we care about
            if (!desiredEvents.contains(event.eventType)) {
                return
            }

            // OPTIMIZATION: Filter TYPE_VIEW_SCROLLED events early
            // ONLY process them if the app is in the Anki Block list (to detect scrolling in blocked apps)
            if (event.eventType == TYPE_VIEW_SCROLLED && !ankiBlockedApps.contains(eventPackageName)) {
                return
            }

            // Update heartbeat for liveness monitoring
            lastEventProcessedTime = System.currentTimeMillis()
            
            // ========== FIX 1: SETTINGS PROCESSING WITH FIXED 10ms DELAY ==========
            // Process Settings packages with FRESH rootInActiveWindow and fixed 10ms delay
            if (isSettings) {
                // Cancel any previous Settings job for this package to coalesce rapid events
                coalescingJobs[eventPackageName]?.cancel()

                val settingsJob = mainScope.launch {
                    delay(10L)  // FIXED 10ms delay for Settings - never changes

                    // FIX: Re-read protection status after delay — user may have disabled it
                    // during those 10ms (or the toggle completed just before this job ran).
                    val protectionStillEnabled = PreferencesHelper.isProtectionEnabled(this@BlockerAccessibilityService)
                    isProtectionEnabled = protectionStillEnabled
                    if (!protectionStillEnabled) {
                        Logger.d(TAG, "Protection disabled — skipping Settings block after delay")
                        return@launch
                    }

                    val freshNode = rootInActiveWindow
                    if (freshNode != null) {
                        try {
                            Logger.d(TAG, "Processing Settings with 10ms delay: $eventPackageName")
                            settingsBlockManager.blockSettingsAccess(eventPackageName, freshNode)

                            // ========== FIX 4: PERIODIC WATCHDOG FOR SETTINGS ==========
                            // Start a watchdog that periodically re-checks while user navigates Settings
                            if (settingsWatchdogJob?.isActive != true) {
                                lastSettingsPackage = eventPackageName
                                settingsWatchdogJob = serviceScope.launch {
                                    try {
                                        while (isActive) {
                                            delay(1000L)  // Check every 1 second
                                            // FIX: Always re-read protection state so the watchdog
                                            // stops immediately when the user disables protection.
                                            val stillProtected = PreferencesHelper.isProtectionEnabled(this@BlockerAccessibilityService)
                                            if (!stillProtected) {
                                                isProtectionEnabled = false
                                                Logger.d(TAG, "Watchdog: protection disabled — stopping")
                                                cancel()
                                                return@launch
                                            }
                                            // Must get fresh node on main thread
                                            withContext(Dispatchers.Main) {
                                                val currentRoot = rootInActiveWindow
                                                val currentPackage = currentRoot?.packageName?.toString()
                                                if (currentPackage == lastSettingsPackage ||
                                                    (currentPackage != null && BlockerConstants.SETTINGS_PACKAGES.contains(currentPackage))) {
                                                    try {
                                                        settingsBlockManager.blockSettingsAccess(currentPackage!!, currentRoot)
                                                        Logger.d(TAG, "Watchdog check for Settings: $currentPackage")
                                                    } catch (e: Exception) {
                                                        Logger.e(TAG, "Periodic settings check failed", e)
                                                    }
                                                } else {
                                                    // User left Settings, cancel watchdog
                                                    Logger.d(TAG, "User left Settings, stopping watchdog")
                                                    cancel()  // Cancel current watchdog coroutine
                                                }
                                            }
                                        }
                                    } finally {
                                        settingsWatchdogJob = null
                                        lastSettingsPackage = null
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Logger.e(TAG, "Error processing Settings event", e)
                        }
                        // NOTE: Do NOT recycle rootInActiveWindow - it's managed by the system
                    } else {
                        Logger.w(TAG, "No root node available for Settings")
                    }
                }

                // Track the job for cancellation on next event
                coalescingJobs[eventPackageName] = settingsJob
                settingsJob.invokeOnCompletion {
                    coalescingJobs.remove(eventPackageName)
                }

                return  // Skip the async path for Settings
            }

            // ========== QUICK PATH FOR INSTALLERS ==========
            if (isInstaller) {
                // Determine if this is the Play Store vs a system installer
                // The Play Store often uses scroll/content changes, while dialogs might be window state changes
                // But for safety against uninstall we process them all aggressively
                val rootSnapshot = rootInActiveWindow?.let { root ->
                    try {
                        AccessibilityNodeInfo.obtain(root)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (rootSnapshot != null) {
                    serviceScope.launch {
                        try {
                            delay(50L) // Tiny delay to let dialog render
                            processEventInBackground(eventPackageName, event.text, rootSnapshot, event.eventType)
                        } finally {
                            try { rootSnapshot.recycle() } catch (e: Exception) {}
                        }
                    }
                }
                return
            }
            
            // ========== ASYNC PATH FOR NON-SETTINGS APPS (Reddit, etc.) ==========
            val currentTime = System.currentTimeMillis()
            
            // Event Coalescing: Batch multiple events for same package within window
            pendingEventsByPackage[eventPackageName] = currentTime
            
            // Cancel previous coalescing job for this package
            coalescingJobs[eventPackageName]?.cancel()

            // Calculate adaptive delay based on event type AND blocking history
            val delayMs = run {
                // Different delays for different event types
                val baseDelay = when (event.eventType) {
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                        // Content changed events (scrolling) - moderate delay
                        300L  // Faster than before but still coalesced
                    }
                    TYPE_VIEW_SCROLLED -> {
                        // Scrolling in Anki blocked apps - FAST response needed
                        50L
                    }
                    TYPE_WINDOW_STATE_CHANGED -> {
                        // Window state changed - quick response
                        50L
                    }
                    else -> {
                        // Other events - standard delay
                        500L
                    }
                }
                
                // Apply adaptive multiplier based on blocking history
                // Force factor to 1.0 for Anki-blocked apps (always fast)
                val adaptiveFactor = when {
                    ankiBlockedApps.contains(eventPackageName) -> 1.0
                    lastBlockTimeByPackage[eventPackageName]?.let { 
                        currentTime - it < 10000L 
                    } == true -> 1.0  // Recent block - use base delay
                    lastBlockTimeByPackage[eventPackageName]?.let { 
                        currentTime - it < 60000L 
                    } == true -> 1.5  // No recent blocks - slow down 50%
                    else -> 2.0  // Long time no blocks - slow down 100%
                }
                
                (baseDelay * adaptiveFactor).toLong()
            }

            // CRITICAL: Capture node on MAIN thread (where we are now) to prevent crashes
            // Android AccessibilityService API is not thread-safe for rootInActiveWindow
            val rootSnapshot = rootInActiveWindow?.let { root ->
                try {
                    // Create thread-safe copy for background processing
                    AccessibilityNodeInfo.obtain(root)
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to obtain node snapshot", e)
                    null
                }
            }
            
            if (rootSnapshot == null) {
                Logger.w(TAG, "No root node available")
                return
            }

            val job = serviceScope.launch {
                try {
                    delay(delayMs)
                    if (isActive) { 
                        // Pass snapshot instead of querying rootInActiveWindow in background
                        processEventInBackground(eventPackageName, event.text, rootSnapshot, event.eventType)
                    }
                } finally {
                    // CRITICAL: Always recycle the snapshot to prevent memory leaks
                    // This ensures recycling happens even if job is cancelled during delay
                    try {
                        rootSnapshot.recycle()
                    } catch (e: Exception) {
                        // Ignore double-recycle errors
                    }
                }
            }
            coalescingJobs[eventPackageName] = job
            
            // Cleanup job from map when done
            job.invokeOnCompletion { 
                coalescingJobs.remove(eventPackageName)
                pendingEventsByPackage.remove(eventPackageName)
            }

        } catch (e: Exception) {
            // Minimal logging in production - only log errors
        }
    }

    /**
     * Processes accessibility event logic in background execution.
     * OPTIMIZED: Now receives pre-captured node snapshot for thread safety.
     */
    private suspend fun processEventInBackground(
        eventPackageName: String, 
        eventText: List<CharSequence>?,
        rootNodeSnapshot: AccessibilityNodeInfo,  // Changed: receives snapshot
        eventType: Int // Passed to detect scrolling/window changes
    ) {
        try {
            // PRIORITY 1: Anki Blocking (Package-based, doesn't need node content)
            // processing this FIRST ensures we block even if the node becomes stale (e.g. fast scrolling)
            if (ankiBlockedApps.contains(eventPackageName)) {
                if (ankiBlockManager.areCardsDue()) {
                     // If overlay is already showing and user is still interacting, press HOME aggressively
                     val overlayAlreadyUp = blockOverlayManager?.isOverlayShowingFast() == true
                     if (overlayAlreadyUp && eventType == TYPE_VIEW_SCROLLED) {
                         Logger.d(TAG, "User scrolling behind overlay — pressing HOME")
                         performGlobalAction(GLOBAL_ACTION_HOME)
                         return
                     }

                     // Aggressive blocking for Anki apps
                     // If user is scrolling (using app) or window changed (floating), press HOME too
                     val isUserActive = eventType == TYPE_VIEW_SCROLLED || 
                                        eventType == TYPE_WINDOWS_CHANGED || 
                                        eventType == TYPE_WINDOW_STATE_CHANGED
                     
                     performBlockAction("Anki Cards Due! Finish your reviews first.", backPressCount = 2)
                     
                     if (isUserActive) {
                         Logger.d(TAG, "Aggressive Anki Block: Performing HOME press")
                         performGlobalAction(GLOBAL_ACTION_HOME)
                     }
                     // If blocked, we don't need to process further
                     return
                }
            }

            // Validate snapshot is still usable for content-based checks (Settings)
            if (!rootNodeSnapshot.refresh()) {
                Logger.w(TAG, "Node snapshot is stale, skipping content-based processing")
                return
            }

            // PRIORITY 2: Uninstall Protection
            if (eventPackageName == BlockerConstants.SYSTEM_PACKAGE_INSTALLER ||
                eventPackageName == BlockerConstants.GOOGLE_PACKAGE_INSTALLER) {
                
                val appName = getString(R.string.app_name)
                val textNodes = rootNodeSnapshot.findAccessibilityNodeInfosByText(appName)
                val uninstallNodes = rootNodeSnapshot.findAccessibilityNodeInfosByText("Uninstall")
                
                if (textNodes.isNotEmpty() && uninstallNodes.isNotEmpty()) {
                    // Check if both the app name and the word "Uninstall" are present on screen.
                    // This is a strong indicator of the uninstaller dialog or play store page
                    Logger.d(TAG, "Detected Uninstallation Attempt for $appName")
                    performBlockAction("Uninstall Attempt Blocked", backPressCount = 2)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }
            }

            // Process the blocking logic for Settings
            if (BlockerConstants.SETTINGS_PACKAGES.contains(eventPackageName)) {
                Logger.d(TAG) { "Checking Settings app access restrictions" }
                settingsBlockManager.blockSettingsAccess(eventPackageName, rootNodeSnapshot)
            }

        } catch (e: Exception) {
            // Minimal error handling
        }
        // NOTE: rootNodeSnapshot recycling is now handled by the caller (onAccessibilityEvent coroutine)
        // to ensure it happens even if this function is never called (due to cancellation).
    }

    override fun onInterrupt() {
        Logger.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        try {
            // Cancel all coroutines
            serviceScope.cancel()
            mainScope.cancel()

            // Unregister listeners
            try {
                getSharedPreferences("blocker_preferences", MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                     createDeviceProtectedStorageContext().getSharedPreferences("blocker_device_prefs", MODE_PRIVATE)
                         .unregisterOnSharedPreferenceChangeListener(this)
                } else {
                     getSharedPreferences("blocker_device_prefs", MODE_PRIVATE)
                         .unregisterOnSharedPreferenceChangeListener(this)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error unregistering preference listeners", e)
            }
        
            // Cleanup overlay 
            Logger.d(TAG, "Service destroyed - cleaning up overlay")
            // Use runBlocking since removeOverlay() is a suspend function
            // This is acceptable in onDestroy() as it's quick and on main thread
            try {
                val manager = blockOverlayManager
                if (manager != null) {
                    kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                        manager.removeOverlay()
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to remove overlay during destroy", e)
            }
            
            Logger.d(TAG, "Blocker accessibility service destroyed")
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onDestroy", e)
        }
        super.onDestroy()
    }
}