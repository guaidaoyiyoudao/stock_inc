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

# ADK AI 会话（RoomSessionService 经反射调用，须保留）
-keep class com.google.adk.kt.sessions.room.** { *; }

# Hilt
-dontwarn dagger.hilt.**

# Backup data model (Gson serialization)
-keep class com.stock.dividend.data.local.backup.** { *; }
-keepclassmembers class com.stock.dividend.data.local.entity.* {
    <fields>;
}

# 交易策略 params JSON 载体（Gson 反射读写嵌套数据类字段名；混淆会把
# halfGainPercent 等变成 a/b，存库键名随 R8 mapping 变化 → 跨版本升级丢参数）
-keep class com.stock.dividend.data.repository.StrategyParams { *; }
-keep class com.stock.dividend.data.repository.StrategyParams$* { *; }

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
