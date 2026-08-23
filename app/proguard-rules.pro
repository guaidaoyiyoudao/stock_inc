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

# 交易策略 params JSON 载体 + 其余 Gson 反射模型（Fundamentals/FinancialStatements/
# LlmAnalysis/KlineBar 等：字段名即 JSON 键，混淆后缓存跨版本解析失败=数据丢失）。
# data.repository 是 Gson 模型最后的聚集地（dto/backup/entity/agent 已各自 keep），
# 整包 keep 杜绝「新增模型忘 keep」这类 release 专属数据缺陷（体积代价可接受：纯 Kotlin，
# 无裁剪收益）。⚠️ 新增 Gson 反射模型若放在其他包，必须同步在本文件加 keep。
-keep class com.stock.dividend.data.repository.** { *; }

# AI Agent 协议层（OpenAiDtos/DeepSeekResponsesProtocol/OpenAiSse 的全部 DTO 经 Gson
# 序列化请求/反序列化响应：字段被混淆后请求变 {"a":"deepseek-v4-flash"}，
# 服务端报 missing field 'model'（实测 2026-08-23 release 包 AI 会话 400 根因）；
# SSE 响应解析同样依赖字段名。整个包 keep（协议适配+工具，无体积敏感类）。
-keep class com.stock.dividend.data.agent.** { *; }

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
