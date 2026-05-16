# Project-level R8/Proguard rules. Defaults from proguard-android-optimize.txt apply.

# Ktor ships io.ktor.util.debug.IntellijIdeaDebugDetector which references
# java.lang.management.ManagementFactory / RuntimeMXBean — JVM-only classes
# that don't exist on Android. Suppress the R8 missing-class error so the
# release build succeeds; the debug-detector code path is unreachable on Android.
-dontwarn io.ktor.util.debug.**
-dontwarn java.lang.management.**

# Compose's FontWeightAdjustmentHelper uses reflection to safely access
# Configuration.fontWeightAdjustment on API 31+ devices. Some OEM Android 12
# builds omit this field despite reporting API 31. Without this keep rule, R8
# can inline or devirtualize the helper in release builds in ways that replace
# the reflection guard with a direct GETFIELD, causing NoSuchFieldError on
# those devices. Keeping the class prevents that optimization path.
-keep class androidx.compose.ui.text.font.FontWeightAdjustmentHelper { *; }
