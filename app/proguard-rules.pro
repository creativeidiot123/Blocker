# Add project specific ProGuard rules here.

# Keep Accessibility Service
-keep class com.ankit.blocker.services.BlockerAccessibilityService { *; }

# Keep Foreground Service
-keep class com.ankit.blocker.services.BlockerForegroundService { *; }

# Keep Broadcast Receivers
-keep class com.ankit.blocker.receivers.** { *; }

# Keep WorkManager Workers
-keep class com.ankit.blocker.workers.** { *; }
-keep class androidx.work.Worker { *; }

# Keep ViewModels (if any)
-keep class com.ankit.blocker.viewmodels.** { *; }

# Keep Data Classes
-keep class com.ankit.blocker.data.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep annotated members
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep PreferencesHelper
-keep class com.ankit.blocker.utils.PreferencesHelper { *; }

# Retain generic type information for reflection
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Don't warn about missing classes often used in libraries but not present
-dontwarn android.support.**
-dontwarn androidx.**