# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class com.stock.dividend.data.remote.dto.** { *; }
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Hilt
-dontwarn dagger.hilt.**

# Backup data model (Gson serialization)
-keep class com.stock.dividend.data.local.backup.** { *; }
-keepclassmembers class com.stock.dividend.data.local.entity.* {
    <fields>;
}

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
