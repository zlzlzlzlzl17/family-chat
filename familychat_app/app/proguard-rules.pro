# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Models are serialized explicitly with org.json, but constructors and fields are
# retained so future R8 optimizations cannot silently alter wire compatibility.
-keep class com.example.chat.**Entity { *; }
-keep class com.example.chat.**Model { *; }

# Native WebRTC entry points are reached through JNI.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# WebRTC M144 resolves these bootstrap classes from JNI_OnLoad. They have no
# Java call sites, so R8 otherwise removes them from minified release builds.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**

# Firebase discovers the messaging service through the manifest/runtime.
-keep class com.example.chat.FamilyFirebaseMessagingService { *; }

# CameraX/Room ship consumer rules; keep database implementations and migrations
# named for useful production stack traces.
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.chat.local.** { *; }

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
