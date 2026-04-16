# Consumer ProGuard rules — applied to host apps that import this SDK
# Keep public API classes so host apps can reference them after minification
-keep public class com.yotech.valtprinter.sdk.** { public *; }

# Room — keep generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
