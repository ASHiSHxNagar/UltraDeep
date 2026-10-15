# ProGuard rules for UltraDeep

# Keep Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Retrofit & Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.ultradeep.music.data.remote.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Media3
-keep class androidx.media3.** { *; }
