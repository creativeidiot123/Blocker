package com.ankit.blocker

import android.app.Application
import com.google.android.material.color.DynamicColors

class BlockerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply Material You monet dynamic colors app-wide if the device supports it
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
