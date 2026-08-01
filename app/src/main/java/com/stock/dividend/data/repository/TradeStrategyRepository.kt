package com.stock.dividend.data.repository

import com.google.gson.Gson
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val risksGson = Gson()

/** risks List<String> → JSON 数组字符串（持久化用）。 */
fun risksToJsonString(risks: List<String>): String = risksGson.toJson(risks)

/** JSON 数组字符串 → List<String>；畸形/null 返回空（红线 #2：永不抛异常）。 */
fun risksFromJson(raw: String?): List<String> = runCatching {
    if (raw.isNullOrBlank()) return emptyList()
    val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
    risksGson.fromJson<List<String>>(raw, type) ?: emptyList()
}.getOrDefault(emptyList())

/**
 * 实体 → 回流引用（sourceNote 不传入，daysAgo 由 now 计算）。
 * 纯函数，便于单测。
 */
fun toUserStrategyRef(
    entity: TradeStrategyEntity,
    now: Long = System.currentTimeMillis()
): UserStrategyRef {
    val daysAgo = ((now - entity.createdAt) / (24L * 3600 * 1000)).toInt().coerceAtLeast(0)
    return UserStrategyRef(
        direction = entity.direction,
        reasoning = entity.reasoning,
        risks = risksFromJson(entity.risks),
        validUntil = entity.validUntil,
        daysAgo = daysAgo
    )
}

/**
 * 持久化封装：策略的存取 + 全局回流查询。网络/DB 异常吞，返回安全空值（红线 #2）。
 */
@Singleton
class TradeStrategyRepository @Inject constructor(
    private val strategyDao: TradeStrategyDao
) {
    suspend fun upsert(entity: TradeStrategyEntity) =
        runCatching { strategyDao.upsert(entity) }.getOrNull()

    /** 全部活跃且未过期的策略（全局回流，不过滤个股）。 */
    suspend fun activeStrategies(): List<TradeStrategyEntity> =
        runCatching { strategyDao.activeStrategies(LocalDate.now().toString()) }
            .getOrDefault(emptyList())
}
