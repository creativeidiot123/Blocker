package com.ankit.blocker.constants

/**
 * Shared constants for the Blocker application.
 */
object BlockerConstants {
    // Settings packages to monitor across OEMs
    val SETTINGS_PACKAGES = setOf(
        "com.android.settings", // Default/AOSP/Pixel/Motorola/Samsung
        "com.oplus.wirelesssettings", "com.oplus.settings", "com.coloros.settings", // Oppo/OnePlus/Realme
        "com.vivo.settings", // Vivo
        "com.miui.securitycenter", // Xiaomi/Poco security center
        "com.huawei.systemmanager" // Huawei
    )

    // Launcher packages for overlay auto-dismissal
    val LAUNCHER_PACKAGES = setOf(
        "com.android.launcher", "com.android.launcher3", // AOSP
        "com.google.android.apps.nexuslauncher", // Pixel
        "com.sec.android.app.launcher", // Samsung
        "com.oppo.launcher", "net.oneplus.launcher", "com.oplus.launcher", "com.coloros.launcher", // Oppo/OnePlus/Realme
        "com.bbk.launcher2", "com.vivo.launcher", // Vivo
        "com.miui.home", "com.mi.android.globallauncher", "com.poco.launcher", "com.mi.android.app.launcher", // Xiaomi/Poco
        "com.huawei.android.launcher", // Huawei
        "com.motorola.launcher3", "com.motorola.home" // Motorola
    )
    
    // Package installers and app stores
    const val SYSTEM_PACKAGE_INSTALLER = "com.android.packageinstaller"
    const val GOOGLE_PACKAGE_INSTALLER = "com.google.android.packageinstaller"

    // Packages that should be blocked entirely (no pattern matching)
    val FULLY_BLOCKED_PACKAGES = setOf<String>()
}