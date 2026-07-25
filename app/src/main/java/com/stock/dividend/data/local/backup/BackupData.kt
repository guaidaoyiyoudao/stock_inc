package com.stock.dividend.data.local.backup

import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.local.entity.TransactionEntity

data class BackupMetadata(
    val appVersion: String,
    val versionCode: Int,
    val exportTimestamp: Long,
    val dbVersion: Int
)

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
    val stockTags: List<StockTagEntity> = emptyList()
)
