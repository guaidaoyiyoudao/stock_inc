package com.stock.dividend.data.local.backup

import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.GRID_TYPE_ARITH
import com.stock.dividend.data.local.entity.GridLevelWeights
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.local.entity.TransactionEntity

data class BackupMetadata(
    val appVersion: String,
    val versionCode: Int,
    val exportTimestamp: Long,
    val dbVersion: Int
)

/**
 * 按备份 [dbVersion] 归一化 grid_plans（恢复路径用，首个按版本修补的先例）。
 *
 * Gson 绕过构造函数反序列化：旧备份缺失的字段 → 基本类型为 Java 默认值（Boolean→false）、
 * 对象类型为 null——**非空列 gridType 为 null 会撞 Room NOT NULL 约束导致整个恢复事务失败**。
 * - `dbVersion < 21`（v20 备份）：notifyEnabled 缺失被置 false → 恢复为 true（当时无此开关，默认开）；
 * - `dbVersion < 22`：gridType 缺失为 null → 恢复为 ARITH（当时只有等差网格）。
 * gridType 的 null 兜底对任何版本都生效（防御损坏数据）。
 * - 自定义档位资金比例（v25 起可空列）：损坏 JSON / 档数与 grids 不匹配 → 置 null
 *   （回退反比默认），避免脏数据让档位计算报「须与档数一致」导致整个计划不可用。
 * - 波段模式四列（v29 起）：swingMode 缺失被置 false（=纯买入，语义默认一致，无需修补）；
 *   swingStepPercent / lastNotifiedSellLevelPrice 可空，缺失为 null 即正确；
 *   swingRatioPercent 为基本类型，缺失被置 0.0（非法）→ 恢复为 30（默认波段仓位比例）。
 */
fun normalizeGridPlans(plans: List<GridPlanEntity>?, dbVersion: Int): List<GridPlanEntity> {
    val list = plans.orEmpty()
    if (list.isEmpty()) return list
    return list.map { plan ->
        // gridType 非空列：Gson 缺字段 → null。必须显式传入 copy——
        // 未指定的参数会读原对象的 null 值，触发 copy 的非空参数检查直接 NPE。
        val type = plan.gridType ?: GRID_TYPE_ARITH
        // 资金比例合法性：解析成功且档数一致才保留
        val weights = plan.levelWeights?.takeIf { raw ->
            GridLevelWeights.parse(raw)?.size == plan.grids
        }
        // 波段仓位比例：Gson 缺字段 → 0.0（非法定值）→ 恢复默认 30
        val swingRatio = plan.swingRatioPercent.takeIf { it > 0.0 && it <= 100.0 } ?: 30.0
        val fixed = plan.copy(
            gridType = type,
            levelWeights = weights,
            swingRatioPercent = swingRatio
        )
        if (dbVersion < 21) fixed.copy(notifyEnabled = true) else fixed
    }
}

/**
 * 按备份 [dbVersion] 归一化 strategy_plans（恢复路径用，模式同 [normalizeGridPlans]）。
 *
 * - `dbVersion < 30` 的备份不可能含策略表，直接返回空表（防御异常数据）；
 * - strategyType 非空列：Gson 缺字段 → null，会撞 Room NOT NULL 约束 → 恢复 MA_DCA；
 * - notifyEnabled 无需修补（v30 起建表即有该列且恒序列化，Gson 不会缺失）；
 * - maPeriod/sellHalf/sellAll/dcaAmount 基本类型缺失被置 0（非法）→ 恢复对应默认值。
 */
fun normalizeStrategyPlans(plans: List<StrategyPlanEntity>?, dbVersion: Int): List<StrategyPlanEntity> {
    val list = plans.orEmpty()
    if (list.isEmpty()) return list
    if (dbVersion < 30) return emptyList()
    return list.map { plan ->
        plan.copy(
            strategyType = plan.strategyType ?: STRATEGY_TYPE_MA_DCA,
            maPeriod = plan.maPeriod.takeIf { it >= 2 } ?: 250,
            sellHalfPercent = plan.sellHalfPercent.takeIf { it > 0.0 } ?: 7.5,
            sellAllPercent = plan.sellAllPercent.takeIf { it > 0.0 } ?: 15.0,
            dcaAmount = plan.dcaAmount.takeIf { it > 0.0 } ?: 1000.0
        )
    }
}

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
    /** 交易策略计划：v30 起新增，旧备份缺失 → null → orEmpty 兜底。 */
    val strategyPlans: List<StrategyPlanEntity> = emptyList(),
    /**
     * 用户配置（SharedPreferences）：外层 key 为 prefs 文件名（如 "llm_prefs"），
     * 内层为该文件全部 key→value。默认空 Map 保证旧备份向后兼容。
     * 仅备份真正的用户配置（LLM 端点 / AI 助手设置），不含可重建的缓存。
     */
    val prefs: Map<String, Map<String, Any?>> = emptyMap()
)
