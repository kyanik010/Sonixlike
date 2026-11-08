# ProGuard rules for SONIX TV

# Keep JavaScript interfaces for WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers class com.example.MainActivity$WebAppInterface {
    public *;
}

-keepclassmembers class com.example.MainActivity$NativePlaybackBridge {
    public *;
}

-keepclassmembers class com.example.XtreamBridge {
    public *;
}

# Keep Media3 / ExoPlayer components and decoders
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep models and reflections
-keep class com.example.model.** { *; }

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
