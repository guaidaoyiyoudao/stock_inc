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

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
