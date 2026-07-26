package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.NotificationRuleDao
import com.stock.dividend.data.local.entity.EVAL_BOOST_YIELD
import com.stock.dividend.data.local.entity.EVAL_MIN_YIELD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRuleRepository @Inject constructor(
    private val dao: NotificationRuleDao
) {
    fun observeGlobalDividendYieldRule(): Flow<NotificationRuleEntity?> =
        dao.observeGlobalRule(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD)

    fun observeStockDividendYieldRule(stockCode: String): Flow<NotificationRuleEntity?> =
        dao.observeStockRule(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD, stockCode)

    fun observeStockRules(stockCode: String): Flow<List<NotificationRuleEntity>> =
        dao.observeStockRules(stockCode)

    suspend fun getEffectiveDividendYieldRules(stockCodes: List<String>): Map<String, NotificationRuleEntity> {
        if (stockCodes.isEmpty()) return emptyMap()
        val rules = dao.getRulesByType(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD)
        val global = rules.firstOrNull { it.stockCode == null && it.enabled }
        val overrides = rules
            .filter { it.stockCode != null && it.enabled }
            .associateBy { it.stockCode!! }

        return stockCodes.mapNotNull { code ->
            val rule = overrides[code] ?: global
            rule?.let { code to it }
        }.toMap()
    }

    suspend fun getEnabledStockRules(stockCodes: List<String>): Map<String, List<NotificationRuleEntity>> {
        if (stockCodes.isEmpty()) return emptyMap()
        return stockCodes.associateWith { code ->
            dao.getStockRules(code).filter { it.enabled }
        }.filterValues { it.isNotEmpty() }
    }

    suspend fun getGlobalDividendYieldRule(): NotificationRuleEntity? =
        dao.getGlobalRule(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD)

    suspend fun getStockDividendYieldRule(stockCode: String): NotificationRuleEntity? =
        dao.getStockRule(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD, stockCode)

    suspend fun saveDividendYieldRule(
        stockCode: String?,
        enabled: Boolean,
        thresholdPercent: Double,
        now: Long = System.currentTimeMillis()
    ) {
        saveRule(
            type = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
            stockCode = stockCode,
            enabled = enabled,
            thresholdValue = thresholdPercent,
            now = now
        )
    }

    // ── 评估门槛（一键评估用，复用 notification_rules 表，无 DB 迁移）──

    fun observeEvalThresholds(): Flow<DividendThresholds> =
        combine(
            dao.observeGlobalRule(EVAL_MIN_YIELD),
            dao.observeGlobalRule(EVAL_BOOST_YIELD)
        ) { minRule, boostRule ->
            DividendThresholds(
                minYieldPercent = minRule?.thresholdPercent
                    ?: DividendThresholds.DEFAULT_MIN_YIELD,
                boostYieldPercent = boostRule?.thresholdPercent
                    ?: DividendThresholds.DEFAULT_BOOST_YIELD
            )
        }

    suspend fun saveEvalThresholds(
        minYieldPercent: Double,
        boostYieldPercent: Double,
        now: Long = System.currentTimeMillis()
    ) {
        saveRule(
            type = EVAL_MIN_YIELD,
            stockCode = null,
            enabled = true,
            thresholdValue = minYieldPercent,
            now = now
        )
        saveRule(
            type = EVAL_BOOST_YIELD,
            stockCode = null,
            enabled = true,
            thresholdValue = boostYieldPercent,
            now = now
        )
    }

    suspend fun saveRule(
        type: String,
        stockCode: String?,
        enabled: Boolean,
        thresholdValue: Double,
        now: Long = System.currentTimeMillis()
    ) {
        val existing = if (stockCode == null) {
            dao.getGlobalRule(type)
        } else {
            dao.getStockRule(type, stockCode)
        }
        val id = defaultRuleId(type, stockCode)
        dao.upsert(
            NotificationRuleEntity(
                id = existing?.id ?: id,
                type = type,
                stockCode = stockCode,
                enabled = enabled,
                thresholdPercent = thresholdValue.coerceAtLeast(0.0),
                lastWasAboveThreshold = existing?.lastWasAboveThreshold,
                lastCheckedAt = existing?.lastCheckedAt,
                lastTriggeredAt = existing?.lastTriggeredAt,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    private fun defaultRuleId(type: String, stockCode: String?): String {
        // 已有稳定 id 的 type（避免迁移后 id 变化导致重复行）
        val stableTypeIds = mapOf(
            NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD to "dividend-yield-threshold",
            EVAL_MIN_YIELD to "eval-min-yield",
            EVAL_BOOST_YIELD to "eval-boost-yield"
        )
        // 未登记的 type 沿用原 type 字符串（保持 PRICE_ABOVE 等既有 id 大小写不变，向后兼容）
        val base = stableTypeIds[type] ?: type
        return stockCode?.let { "stock-$it-$base" } ?: "global-$base"
    }

    suspend fun updateRuleEvaluationState(
        rule: NotificationRuleEntity,
        lastWasAboveThreshold: Boolean,
        checkedAt: Long,
        triggeredAt: Long?
    ) {
        dao.upsert(
            rule.copy(
                lastWasAboveThreshold = lastWasAboveThreshold,
                lastCheckedAt = checkedAt,
                lastTriggeredAt = triggeredAt ?: rule.lastTriggeredAt,
                updatedAt = checkedAt
            )
        )
    }
}
