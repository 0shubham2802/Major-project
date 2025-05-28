# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:
-keep class com.google.android.gms.location.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
<<<<<<< HEAD
=======

# Keep the AR Core classes
-keep class com.google.ar.** { *; }
-keep interface com.google.ar.** { *; }

# Keep Google Maps components
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }

# Keep local model classes
-keep class com.google.ar.core.codelabs.hellogeospatial.model.** { *; }

# Keep any classes/interfaces with onXxx callbacks from AR/Maps
-keepclasseswithmembers class * {
    public void onMapReady(com.google.android.gms.maps.GoogleMap);
    public void onArSessionFailed(com.google.ar.core.exceptions.UnavailableException);
}

# Keep serialization classes
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# Keep lifecycle methods
-keepclassmembers class * implements androidx.lifecycle.LifecycleObserver {
    <methods>;
}

# General Android optimizations
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# Keep necessary resources
-keepclassmembers class **.R$* {
    public static <fields>;
}
>>>>>>> c151f18695107cbc89324a0da3b7f4399532b9e2
