# Project-level R8/Proguard rules. Defaults from proguard-android-optimize.txt apply.

# Ktor ships io.ktor.util.debug.IntellijIdeaDebugDetector which references
# java.lang.management.ManagementFactory / RuntimeMXBean — JVM-only classes
# that don't exist on Android. Suppress the R8 missing-class error so the
# release build succeeds; the debug-detector code path is unreachable on Android.
-dontwarn io.ktor.util.debug.**
-dontwarn java.lang.management.**

# Type-safe Navigation Compose routes. Each NavHost destination is a top-level
# @Serializable object / data class in app.clothescast.ui.nav (TodayRoute,
# OnboardingRoute, PairingRoute, the Settings*Dest objects, SettingsGraph).
# Compose Navigation resolves a route's KSerializer *reflectively, by KClass*,
# while building the graph at NavHost composition. R8's optimizing+obfuscating
# release build strips the synthetic serializer() / INSTANCE members of the
# parameterless @Serializable objects — the kotlinx-serialization bundled rules
# only keep a serializer that's otherwise statically reachable, and a route used
# solely via composable<Route> / navigate(Route) isn't — so the reflective
# lookup throws "Serializer for class <obfuscated> is not found" and the app
# dies on launch. (TodayRoute survives because its data-class $$serializer is
# directly referenced; the bare object routes don't.) Keep every route type and
# its generated serialization machinery; the classes are tiny.
-keep @kotlinx.serialization.Serializable class app.clothescast.ui.nav.** { *; }
-keep class app.clothescast.ui.nav.**$$serializer { *; }
-keep class app.clothescast.ui.nav.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

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
-dontwarn reactor.blockhound.**

# Keeping all of io.netty.** (below) brings every optional codec / native /
# Graal / JDK-internal class that Netty conditionally references back into the
# R8 input. None are reachable from an MQTT publisher that just opens a TLS
# socket and publishes a UTF-8 string — they're all alternative codec paths
# (Brotli, Zstd, jzlib, LZ4, LZF, LZMA), protobuf serialization, JBoss
# marshalling, GraalVM/SVM substitution annotations, and JDK-private SSL
# self-signed cert utilities — but their references would trip R8 without
# these warnings suppressed. List harvested from missing_rules.txt; if a
# Netty version bump adds new optional integrations, re-run assembleDebug
# and append any new ones here.
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.google.protobuf.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.jboss.marshalling.**
-dontwarn sun.security.x509.**

# HiveMQ MQTT Client wires its Netty pipeline through an internal Dagger 2
# graph. The generated factories (e.g. *_Factory, DoubleCheck Provider chains)
# are resolved by name at runtime when buildAsync() constructs the client, so
# R8 must not strip or rename them. Without these rules the publisher crashes
# at the first publishWith() call with NoClassDefFoundError on an obfuscated
# Dagger factory (z5.b etc.). Keeping the whole package is heavier than the
# minimum set but is the only reliable shape — picking individual generated
# classes is brittle across HiveMQ versions.
-keep class com.hivemq.client.** { *; }
-keepclassmembers class com.hivemq.client.** { *; }

# Netty resolves its logger and event-loop implementations via service-loader
# style reflection on a string class name. Without the keep rules below R8
# can devirtualize / strip the JDK-logger backstop, leaving HiveMQ unable to
# install a logger at startup. The transitive Netty deps the MQTT client
# uses (buffer, codec, handler, transport, resolver) all rely on this path.
#
# Beyond logging, Netty's buffer layer reflectively resolves methods like
# `AbstractByteBufAllocator#toLeakAwareBuffer` from its `ResourceLeakDetector`
# static init — R8 has no way to see that call graph and will both rename and
# strip the target methods, blowing up the encoder construction chain at the
# first publish with
# "Can't find '[toLeakAwareBuffer]' in <obfuscated>". The reliable fix is to
# keep all of `io.netty.**` intact: the buffer/codec/handler classes are
# transitively referenced through reflection from initializers we can't fully
# enumerate, and trying to pick individual classes is brittle across Netty
# point releases. The `-dontwarn` lines above already permit the optional
# integrations (epoll / tcnative / websockets / log4j / Jetty ALPN / proxy
# / BlockHound) to keep references to classes we don't ship.
-keep class io.netty.** { *; }
-keepclassmembers class io.netty.** { *; }

# JCTools (org.jctools) is Netty's high-performance queue library, used for
# MpscArrayQueue / MpmcArrayQueue inside Netty's event loop and inside
# HiveMQ's outgoing-QoS handler. JCTools resolves the byte offset of fields
# like `consumerIndex` and `producerIndex` via
# Unsafe.objectFieldOffset(getDeclaredField(...)) in each queue base class's
# static initializer. R8 can't see that reflective name and will rename the
# field — JCTools then crashes at first class-load with
# "NoSuchFieldException: No field consumerIndex in class L<obfuscated>;".
# Keeping the whole package preserves the field names the Unsafe lookup
# depends on; trying to pick individual classes is brittle across JCTools
# point releases.
-keep class org.jctools.** { *; }
-keepclassmembers class org.jctools.** { *; }

# RxJava 2 (io.reactivex.**) is HiveMQ's reactive backbone — every connect /
# publish / subscribe call goes through a Single / Observable before the
# CompletableFutures we await on. RxJava resolves AtomicFieldUpdaters via
# class+field names, so it falls under the same reflective-Unsafe pattern as
# JCTools above. org.reactivestreams is the public interface surface; kept
# for completeness so subclass relationships survive.
-keep class io.reactivex.** { *; }
-keepclassmembers class io.reactivex.** { *; }
-keep class org.reactivestreams.** { *; }
