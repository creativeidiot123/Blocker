package com.ankit.blocker.managers

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.ankit.blocker.R
import com.ankit.blocker.constants.BlockerConstants

class SettingsBlockManager(
    private val context: Context,
    private val blockedContentGoBack: (String) -> Unit,
) {
    // Cache app name to avoid resource lookups in tight loops
    private val appName: String by lazy { context.getString(R.string.app_name) }

    companion object {
        private const val TAG = "Blocker.SettingsBlockManager"

        // Device Admin related IDs and text
        private const val ADMIN_NAME_ID = "com.android.settings:id/admin_name"
        private const val ADMIN_SUMMARY_ID = "com.android.settings:id/admin_summary"
        

        // Safety limit for recursion depth
        private const val MAX_RECURSION_DEPTH = 50
    }

    /**
     * Checks if a blocked settings feature is open and applies restrictions.
     * Uses optimized single-pass pattern matching.
     *
     * @param packageName The package name of the current app in focus.
     * @param node The root AccessibilityNodeInfo of the current screen.
     */
    fun blockSettingsAccess(
        packageName: String,
        node: AccessibilityNodeInfo,
    ) {
        // Package validation is done in the service layer, so skip it here for performance
        // Protection enabled check is also done in service layer
        
        // Check if this package is fully blocked (no pattern matching needed)
        if (isPackageFullyBlocked(packageName)) {
            val blockReason = "App blocked: $packageName"
            blockedContentGoBack.invoke(blockReason)
            return
        }

        // Single-pass traversal that checks everything
        val blockReason = scanForBlocking(node, packageName, depth = 0)
        
        blockReason?.let { reason ->
            blockedContentGoBack.invoke(reason)
        }
    }

    /**
     * Checks if a package should be blocked entirely without pattern matching.
     *
     * @param packageName The package name to check.
     * @return True if package should be fully blocked, false otherwise.
     */
    private fun isPackageFullyBlocked(packageName: String): Boolean {
        return BlockerConstants.FULLY_BLOCKED_PACKAGES.contains(packageName)
    }

    /**
     * Unified single-pass traversal of the accessibility tree.
     * Checks for BOTH view IDs (Device Admin) and text patterns (Other settings) simultaneously.
     * Returns the blocking reason string immediately if found, or null if nothing found.
     * SAFE: Limited recursion depth to prevent stack overflow.
     */
    fun scanForBlocking(rootNode: AccessibilityNodeInfo, packageName: String, depth: Int): String? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        
        var visitedCount = 0
        val MAX_VISITED_NODES = 200 // Safety break

        // We check if Blocker's name is on screen as an indicator of targeting Blocker Settings
        var isAppNamePresent = false

        try {
            // First pass to see if App name is anywhere in the tree
            val textNodes = rootNode.findAccessibilityNodeInfosByText(appName)
            isAppNamePresent = textNodes.isNotEmpty()

            while (!queue.isEmpty() && visitedCount < MAX_VISITED_NODES) {
                val node = queue.poll() ?: continue
                visitedCount++

                try {
                    // Check current node
                    val reason = checkNode(node, packageName, isAppNamePresent)
                    if (reason != null) {
                        return reason
                    }

                    // Add children to queue
                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                } finally {
                    // Critical: Recycle the node after we are done with it.
                    // rootNode should NOT be recycled by us as it belongs to the caller.
                    if (node != rootNode) {
                        node.recycle()
                    }
                }
            }
        } finally {
            // Clean up any remaining nodes in queue if we exited early (found match or limit reached)
            while (!queue.isEmpty()) {
                val n = queue.poll()
                if (n != null && n != rootNode) {
                    n.recycle()
                }
            }
        }
        
        return null
    }

    /**
     * Checks a single node for all blocking conditions.
     */
    private fun checkNode(node: AccessibilityNodeInfo, packageName: String, isAppNamePresent: Boolean): String? {
        val viewId = node.viewIdResourceName
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        
        // 1. Check Device Admin (Targeted to Blocker)
        if (viewId != null) {
            if (viewId == ADMIN_NAME_ID && text == appName) {
                return "Device Admin Settings: Blocker"
            }
            // Deactivation confirmation screen in modern android targets the app name + "Deactivate this device admin app" text
        }

        // 2. Check Text Patterns
        if (!text.isNullOrEmpty()) {
            val match = checkPatterns(text, packageName, isAppNamePresent)
            if (match != null) return match
        }
        
        if (!contentDesc.isNullOrEmpty()) {
            val match = checkPatterns(contentDesc, packageName, isAppNamePresent)
            if (match != null) return match
        }

        return null
    }


    private fun checkPatterns(text: String, packageName: String, isAppNamePresent: Boolean): String? {
        
        // Targeted Device Admin Deactivation Check
        if (isAppNamePresent && (text.contains("Deactivate this device admin app", ignoreCase = true) || text.contains("Deactivate", ignoreCase = true))) {
             if (text == "Deactivate this device admin app" || text.contains("device admin", ignoreCase = true)) {
                 return "Device Admin Settings: Deactivation Attempt"
             }
        }

        // Targeted Accessibility Settings Check
        // If the accessibility description of Blocker is visible AND Blocker's name is on screen,
        // it means the user is on Blocker's specific accessibility settings page.
        val accessibilityDesc = context.getString(R.string.accessibility_description)
        if (isAppNamePresent && text.contains(accessibilityDesc, ignoreCase = true)) {
            return "Accessibility Settings: Blocker"
        }

        return null
    }

}