# ==============================================================================
# AirControl ProGuard / R8 Configuration
# ==============================================================================
# Tested with R8 full mode + resource shrinking.
# To verify: build release APK, install on device, run full gesture session.
# ==============================================================================

# ---------- AutoValue (transitive via MediaPipe) ----------
# AutoValue annotation processors are on the classpath but not needed at runtime.
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**

# ---------- MediaPipe Tasks Vision ----------
# MediaPipe - keep only the classes we actually use
-keep class com.google.mediapipe.tasks.vision.handlandmarker.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep interface com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
# Native methods called from MediaPipe native code
-keepclasseswithmembernames class * {
    native <methods>;
}
# MediaPipe protocol buffer generated code
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# ---------- Hilt / Dagger ----------
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-dontwarn dagger.hilt.**

# ---------- Jetpack Compose ----------
# Compose compiler adds its own ProGuard rules; no manual keeps needed.
-dontwarn androidx.compose.**

# ---------- Kotlin ----------
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { *; }
-keepclassmembers class kotlin.Metadata { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlin.concurrent.**
-dontwarn kotlin.reflect.**

# ---------- CameraX ----------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---------- DataStore ----------
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---------- Accessibility Service ----------
# Keep accessibility service class and its methods
-keep class com.aircontrol.accessibility.GestureControlAccessibilityService { *; }
-keep class com.aircontrol.accessibility.ActionDispatcher { *; }
-keep class com.aircontrol.accessibility.GestureAction { *; }

# ---------- Android Components ----------
-keep class com.aircontrol.** extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Application { *; }

# ---------- Timber ----------
# In release builds, strip debug logging calls for performance
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
# Keep error/warning logs for crash debugging
-keep class timber.log.Timber { *; }
-dontwarn timber.log.**

# ---------- Native Libraries ----------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------- General Android ----------
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# ---------- Serialization ----------
# Keep GestureAction enum values for DataStore serialization
-keepclassmembers enum com.aircontrol.accessibility.GestureAction {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Keep HandPreference enum values
-keepclassmembers enum com.aircontrol.data.model.HandPreference {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep enum classes used in JSON serialization/deserialization
-keepclassmembers enum com.aircontrol.data.model.CustomGesturePose {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.aircontrol.data.model.CustomGestureDirection {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.aircontrol.data.model.FingerType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Custom gesture models — used in JSON serialization
-keep class com.aircontrol.data.model.CustomGesture { *; }
-keep class com.aircontrol.data.model.CustomGestureTrigger { *; }
-keep class com.aircontrol.data.model.CustomGestureTrigger$PoseWithDirection { *; }
-keep class com.aircontrol.data.model.CustomGestureTrigger$FingerCount { *; }

# ---------- Reflection Safety ----------
# Keep any class with @Inject annotation
-keepclassmembers class * {
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <methods>;
}

# ---------- Debug ----------
# Remove debug logs in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
