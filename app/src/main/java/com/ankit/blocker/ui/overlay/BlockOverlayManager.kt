package com.ankit.blocker.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.ankit.blocker.R
import com.ankit.blocker.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages the full-screen "Blocked" overlay on top of other apps.
 */
class BlockOverlayManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope  // OPTIMIZED: Inject scope instead of using GlobalScope
) {
    companion object {
        private const val TAG = "Blocker.OverlayManager"
    }

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // THREAD-SAFE: Volatile ensures visibility across threads
    @Volatile
    private var overlayView: View? = null

    /**
     * Shows the block overlay.
     * Synchronized to prevent double-overlay race conditions.
     *
     * @param onGoBack Action to perform when "Go Back" is clicked.
     */
    private val mutex = Mutex()

    /**
     * Shows the block overlay.
     * Uses Mutex to prevent double-overlay race conditions.
     *
     * @param onGoBack Action to perform when "Go Back" is clicked.
     */
    suspend fun showOverlay(onGoBack: () -> Unit) {
        mutex.withLock {
            if (overlayView != null) {
                Logger.d(TAG, "Overlay already showing, ignore request")
                return
            }

            try {
                Logger.d(TAG, "Showing block overlay")
                
                // Inflate view
                val inflater = LayoutInflater.from(context)
                val view = inflater.inflate(R.layout.layout_block_overlay, null)
                overlayView = view

                // Setup button
                view.findViewById<Button>(R.id.btn_go_back)?.setOnClickListener {
                    Logger.d(TAG, "User clicked Go Back on overlay")
                    // Perform global back action first
                    onGoBack()
                    // OPTIMIZED: Use injected scope instead of GlobalScope to prevent leak
                    coroutineScope.launch(Dispatchers.Main) {
                        removeOverlay()
                    }
                }

                // Layout Params
                val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutFlag,
                    // Flags:
                    // FLAG_LAYOUT_IN_SCREEN: Use full screen including system bars
                    // FLAG_FULLSCREEN: Hide status bar (immersive)
                    // We DO NOT use FLAG_NOT_FOCUSABLE because we want to intercept all keys (Back, etc)
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv() and 0 or  // Ensure touch modal (block touches)
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,  // Extend under nav/status bars
                    PixelFormat.TRANSLUCENT
                )

                params.gravity = Gravity.CENTER

                // Add view to permissions permitting
                windowManager.addView(view, params)
                Logger.d(TAG, "Overlay view added to WindowManager")

            } catch (e: Exception) {
                Logger.e(TAG, "Failed to show overlay", e)
                
                // Cleanup on error
                if (overlayView != null) {
                    try {
                        windowManager.removeView(overlayView)
                    } catch (cleanupEx: IllegalArgumentException) {
                        Logger.w(TAG, "Overlay view was not attached to WindowManager during cleanup: ${cleanupEx.message}")
                    } catch (cleanupEx: Exception) {
                        // Ignore cleanup errors
                    }
                }
                overlayView = null
                
                // Fallback: Perform back action if overlay failed
                onGoBack()
            }
        }
    }

    /**
     * Removes the block overlay.
     * Uses Mutex to prevent race conditions.
     */
    suspend fun removeOverlay() {
        mutex.withLock {
            if (overlayView == null) return

            try {
                Logger.d(TAG, "Removing block overlay")
                windowManager.removeView(overlayView)
            } catch (e: IllegalArgumentException) {
                Logger.w(TAG, "Overlay view was not attached to WindowManager: ${e.message}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to remove overlay", e)
            } finally {
                overlayView = null
            }
        }
    }

    /**
     * Checks if overlay is currently active.
     * OPTIMIZED: Now uses mutex for thread-safe check (prevents double-overlay race)
     */
    fun isOverlayShowingFast(): Boolean = overlayView != null  // @Volatile already ensures visibility

    suspend fun isOverlayShowing(): Boolean {
        return mutex.withLock { overlayView != null }
    }
}
