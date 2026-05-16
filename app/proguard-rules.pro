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

# HiveMQ MQTT Client pulls Netty in. Netty's binaries reference a long list of
# optional integrations (epoll, tcnative, log4j adapters, Jetty ALPN/NPN,
# BlockHound, websocket / proxy handlers) that aren't on the Android classpath
# because the MQTT bridge uses plain-TCP / TLS-via-JSSE, no websockets, no
# proxies, no native epoll. R8 surfaces every cross-reference as a hard error;
# these -dontwarn lines tell it those gaps are intentional. List taken from
# app/build/outputs/mapping/<variant>/missing_rules.txt after a fresh
# assembleDebug. If you bump the HiveMQ / Netty version and the list grows,
# re-run that build and append any new -dontwarn lines here.
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.handler.codec.http.**
-dontwarn io.netty.handler.codec.http.websocketx.**
-dontwarn io.netty.handler.proxy.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn reactor.blockhound.integration.**
