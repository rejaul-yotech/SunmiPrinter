# SDK internal ProGuard rules
# Keep public API surface
-keep public class com.yotech.valtprinter.sdk.** { public *; }

# Keep data classes used in public API (Gson serialization)
-keepclassmembers class com.yotech.valtprinter.sdk.** {
    <fields>;
}

# Kotlin
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keep class kotlin.Metadata { *; }
