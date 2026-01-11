# Add project specific ProGuard rules here.

# Keep Whisper JNI methods
-keep class com.securevox.app.whisper.** { *; }

# Keep Room entities
-keep class com.securevox.app.data.model.** { *; }
