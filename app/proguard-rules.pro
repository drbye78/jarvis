# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.jarvis.assistant.contracts.**$$serializer { *; }
-keepclassmembers class com.jarvis.assistant.contracts.** { *** Companion; }
-keepclasseswithmembers class com.jarvis.assistant.contracts.** { kotlinx.serialization.KSerializer serializer(...); }
# Broadened: any @Serializable class anywhere (tool DTOs may live outside contracts)
-keepclasseswithmembers @kotlinx.serialization.Serializable class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class * { *** Companion; }

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

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
