package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.NotificationRuleDao
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import kotlinx.coroutines.flow.Flow
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
        if (type == NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD) {
            return stockCode?.let { "stock-$it-dividend-yield-threshold" } ?: "global-dividend-yield-threshold"
        }
        return stockCode?.let { "stock-$it-$type" } ?: "global-$type"
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
