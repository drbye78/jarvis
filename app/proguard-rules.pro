# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.jarvis.assistant.wire.**$$serializer { *; }
-keepclassmembers class com.jarvis.assistant.wire.** { *** Companion; }
-keepclasseswithmembers class com.jarvis.assistant.wire.** { kotlinx.serialization.KSerializer serializer(...); }

# Protobuf
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# gRPC
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**
-keep class com.jarvis.assistant.grpc.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Picovoice Porcupine
-keep class ai.picovoice.porcupine.** { *; }

# Sherpa-ONNX Keyword Spotting (prebuilt AAR)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# androidx.media (MUSIC lane): MediaBrowserCompat / MediaControllerCompat talk
# to OTHER apps' services via Binder + the versioned-parcelable protocol —
# reflection-driven across process boundaries, so the compat surface must
# survive shrinking. The library ships consumer rules, but the media item /
# session callback generics occasionally trip aggressive configurations.
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
-dontwarn android.support.v4.media.**

# Yandex MapKit: the dependency declaration excludes GMS (play-services-location
# + play integrity), and the AAR does not embed them. Eight classes under
# com.yandex.runtime.{sensors,attestation_storage}.internal still reference those
# types in their constant pools, though, so R8 warns about the now-missing
# classes even though the search/routing call paths never touch them.
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.play.**

# R13 management-surface spike. Netty's io.netty.util.internal.Hidden$NettyBlockHoundIntegration
# contains a compile-time reference (and a META-INF/services entry) for the
# optional reactor-blockhound diagnostic integration, which is NOT on the
# classpath. It is never loaded at runtime; silence R8's missing-class error.
# The only rule R8 actually asked for (missing_rules.txt listed just this one).
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
