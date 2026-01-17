# Add project specific ProGuard rules here.

# Keep Whisper JNI methods and native library
-keep class com.securevox.app.whisper.** { *; }
-keepclassmembers class com.securevox.app.whisper.** {
    native <methods>;
}

# Keep Room entities and DAOs
-keep class com.securevox.app.data.model.** { *; }
-keep class com.securevox.app.data.local.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep serialization
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Don't warn about missing classes
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
