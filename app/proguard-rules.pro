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

# AI Agent 协议层（OpenAiDtos/DeepSeekResponsesProtocol/OpenAiSse 的全部 DTO 经 Gson
# 序列化请求/反序列化响应：字段被混淆后请求变 {"a":"deepseek-v4-flash"}，
# 服务端报 missing field 'model'（实测 2026-08-23 release 包 AI 会话 400 根因）；
# SSE 响应解析同样依赖字段名。整个包 keep（协议适配+工具，无体积敏感类）。
-keep class com.stock.dividend.data.agent.** { *; }

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
