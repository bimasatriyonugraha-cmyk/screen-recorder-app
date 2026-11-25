# Keep the MediaProjection / MediaRecorder wiring intact for minified builds.
-keep class com.example.screenrecorder.ScreenRecordService { *; }
-keep class com.example.screenrecorder.RecorderConfig { *; }

# Nothing else is reflective in this app, so the AGP default rules are enough.
-dontwarn org.jetbrains.annotations.**
