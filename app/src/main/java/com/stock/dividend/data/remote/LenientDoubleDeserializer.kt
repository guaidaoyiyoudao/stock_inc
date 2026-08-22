package com.stock.dividend.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

/**
 * 东财系接口专用 Double 容错反序列化：字符串（"-" 停牌/退市占位、异常文本）与
 * 非有限数字（NaN/Infinity）一律读成 null——单条记录降级而非整批解析失败。
 *
 * 背景（2026-08-20 审计实测）：clist 对退市/停牌股全字段返回 "-"，默认 Gson 对
 * `Double?` 字段抛 NumberFormatException 且发生在整个 diff 数组反序列化阶段——
 * **一条脏记录毒死整个列表**（板块列表/行业个股/全市场榜单全部返回空）。东财 DTO
 * 数值字段全为可空 Double，null 语义即「字段缺失」，与既有 takeIfFinite 降级路径
 * 一致（红线 #2 / 宪法 III 不臆造）。仅用于东财/腾讯系 Retrofit（见 NetworkModule.marketGson），
 * LLM 接口走标准 OpenAI 协议不共享。
 */
internal object LenientDoubleDeserializer : JsonDeserializer<Double?> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Double? {
        if (json.isJsonNull) return null
        if (!json.isJsonPrimitive) return null
        // "-" 等占位 → null；正常数字字符串（如 "1.5"）照常解析；NaN/Infinity 降 null
        return runCatching { json.asString.toDoubleOrNull() }.getOrNull()
            ?.takeIf { it.isFinite() }
    }
}

/** 注册 [LenientDoubleDeserializer] 的 Gson（东财/腾讯系 Retrofit 共享；测试亦用此构造锁定行为）。 */
internal fun lenientMarketGson(): Gson = GsonBuilder()
    .registerTypeAdapter(Double::class.javaObjectType, LenientDoubleDeserializer)
    .registerTypeAdapter(Double::class.java, LenientDoubleDeserializer)
    .create()
