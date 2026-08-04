package com.stock.dividend.data.local.backup

import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.local.entity.TransactionEntity

data class BackupMetadata(
    val appVersion: String,
    val versionCode: Int,
    val exportTimestamp: Long,
    val dbVersion: Int
)

/**
 * 备份校验摘要：元信息 + 各表记录数，供导入确认对话框预览。
 * counts 仅用于展示，不参与序列化（由 [com.stock.dividend.data.repository.BackupRepository.validateBackup] 计算）。
 */
data class BackupSummary(
    val metadata: BackupMetadata,
    val counts: BackupCounts
)

/** 备份内各业务表的记录条数。 */
data class BackupCounts(
    val stocks: Int = 0,
    val dividends: Int = 0,
    val transactions: Int = 0,
    val dividendIncomeRecords: Int = 0,
    val tradeStrategies: Int = 0,
    val industryTargets: Int = 0,
    /** 用户配置项数（LLM 端点 / AI 助手设置等 SharedPreferences 键值对总数）。 */
    val settings: Int = 0
) {
    /** 业务记录总数（用于一句话摘要，不含 settings，避免与「记录数」语义混淆）。 */
    val total: Int get() = stocks + dividends + transactions +
        dividendIncomeRecords + tradeStrategies + industryTargets
}

data class BackupContainer(
    val metadata: BackupMetadata,
    val stocks: List<StockEntity>,
    val dividends: List<DividendEntity>,
    val fireGoals: List<FireGoalEntity>,
    val dividendIncomeRecords: List<DividendIncomeRecordEntity>,
    val transactions: List<TransactionEntity>,
    val achievements: List<AchievementEntity>,
    val livingExpenseItems: List<LivingExpenseItemEntity>,
    val notificationRules: List<NotificationRuleEntity>,
    val stockTags: List<StockTagEntity> = emptyList(),
    val tradeStrategies: List<TradeStrategyEntity> = emptyList(),
    // 行业目标配比：v2 起新增，默认空以保证旧备份向后兼容（反序列化缺失字段 → null → emptyList）
    val industryTargets: List<IndustryTargetEntity> = emptyList(),
    /** 网格交易计划：v20 起新增，旧备份缺失 → null → orEmpty 兜底。 */
    val gridPlans: List<GridPlanEntity> = emptyList(),
    /**
     * 用户配置（SharedPreferences）：外层 key 为 prefs 文件名（如 "llm_prefs"），
     * 内层为该文件全部 key→value。默认空 Map 保证旧备份向后兼容。
     * 仅备份真正的用户配置（LLM 端点 / AI 助手设置），不含可重建的缓存。
     */
    val prefs: Map<String, Map<String, Any?>> = emptyMap()
)
