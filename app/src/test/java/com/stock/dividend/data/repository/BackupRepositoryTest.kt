package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import com.stock.dividend.data.local.backup.BackupContainer
import com.stock.dividend.data.local.backup.BackupMetadata
import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import org.junit.Test

class BackupRepositoryTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `json round-trip preserves all entity data`() {
        val metadata = BackupMetadata(
            appVersion = "2.0.0",
            versionCode = 3,
            exportTimestamp = 1700000000000L,
            dbVersion = 10
        )

        val container = BackupContainer(
            metadata = metadata,
            stocks = listOf(
                StockEntity(
                    code = "sh.600036",
                    name = "招商银行",
                    marketCode = "1",
                    addedAt = 1000L,
                    lastUpdated = 2000L,
                    shares = 100,
                    yieldPeriod = "3",
                    costPerShare = 35.5
                ),
                StockEntity(
                    code = "sz.000001",
                    name = "平安银行",
                    marketCode = "0",
                    addedAt = 3000L,
                    lastUpdated = null,
                    shares = 50,
                    yieldPeriod = "5",
                    costPerShare = 12.0
                )
            ),
            dividends = listOf(
                DividendEntity(
                    id = "div1",
                    stockCode = "sh.600036",
                    reportDate = "2025-07-10",
                    cashPerShare = 1.972,
                    dividendYield = 5.5,
                    exDividendDate = "2025-07-15",
                    recordDate = "2025-07-14",
                    planNoticeDate = null,
                    planStatus = "已实施"
                )
            ),
            fireGoals = listOf(
                FireGoalEntity(
                    id = 1L,
                    targetAmount = 120000.0,
                    createdAt = 1000L,
                    updatedAt = 2000L
                )
            ),
            dividendIncomeRecords = listOf(
                DividendIncomeRecordEntity(
                    id = "rec1",
                    stockCode = "sh.600036",
                    year = 2025,
                    date = "2025-07-15",
                    amount = 197.2,
                    exDividendDate = "2025-07-15",
                    source = "auto",
                    note = null,
                    createdAt = 2000L,
                    updatedAt = 2000L
                )
            ),
            transactions = listOf(
                TransactionEntity(
                    id = 1L,
                    stockCode = "sh.600036",
                    type = "BUY",
                    shares = 100,
                    price = 35.0,
                    date = "2025-01-10",
                    createdAt = 1000L
                )
            ),
            achievements = listOf(
                AchievementEntity(id = "first_stock", unlockedAt = 1000L)
            ),
            livingExpenseItems = listOf(
                LivingExpenseItemEntity(
                    id = 1L,
                    name = "房租",
                    amount = 3000.0,
                    period = "MONTHLY",
                    sortOrder = 0,
                    createdAt = 1000L,
                    updatedAt = 2000L
                )
            ),
            notificationRules = listOf(
                NotificationRuleEntity(
                    id = "rule1",
                    type = "DIVIDEND_YIELD_THRESHOLD",
                    stockCode = null,
                    enabled = true,
                    thresholdPercent = 5.0,
                    lastWasAboveThreshold = null,
                    lastCheckedAt = null,
                    lastTriggeredAt = null,
                    createdAt = 1000L,
                    updatedAt = 2000L
                )
            )
        )

        val json = gson.toJson(container)
        val restored = gson.fromJson(json, BackupContainer::class.java)

        assertThat(restored).isNotNull()
        assertThat(restored.metadata.appVersion).isEqualTo("2.0.0")
        assertThat(restored.metadata.versionCode).isEqualTo(3)
        assertThat(restored.metadata.exportTimestamp).isEqualTo(1700000000000L)
        assertThat(restored.metadata.dbVersion).isEqualTo(10)

        assertThat(restored.stocks).hasSize(2)
        assertThat(restored.stocks[0].code).isEqualTo("sh.600036")
        assertThat(restored.stocks[0].name).isEqualTo("招商银行")
        assertThat(restored.stocks[0].shares).isEqualTo(100)
        assertThat(restored.stocks[0].costPerShare).isEqualTo(35.5)
        assertThat(restored.stocks[0].yieldPeriod).isEqualTo("3")
        assertThat(restored.stocks[1].lastUpdated).isNull()

        assertThat(restored.dividends).hasSize(1)
        assertThat(restored.dividends[0].cashPerShare).isEqualTo(1.972)
        assertThat(restored.dividends[0].planNoticeDate).isNull()

        assertThat(restored.fireGoals).hasSize(1)
        assertThat(restored.fireGoals[0].targetAmount).isEqualTo(120000.0)

        assertThat(restored.dividendIncomeRecords).hasSize(1)
        assertThat(restored.dividendIncomeRecords[0].amount).isEqualTo(197.2)
        assertThat(restored.dividendIncomeRecords[0].source).isEqualTo("auto")

        assertThat(restored.transactions).hasSize(1)
        assertThat(restored.transactions[0].type).isEqualTo("BUY")
        assertThat(restored.transactions[0].price).isEqualTo(35.0)

        assertThat(restored.achievements).hasSize(1)
        assertThat(restored.achievements[0].id).isEqualTo("first_stock")

        assertThat(restored.livingExpenseItems).hasSize(1)
        assertThat(restored.livingExpenseItems[0].name).isEqualTo("房租")
        assertThat(restored.livingExpenseItems[0].period).isEqualTo("MONTHLY")

        assertThat(restored.notificationRules).hasSize(1)
        assertThat(restored.notificationRules[0].thresholdPercent).isEqualTo(5.0)
        assertThat(restored.notificationRules[0].enabled).isTrue()
    }

    @Test
    fun `json round-trip handles empty collections`() {
        val metadata = BackupMetadata(
            appVersion = "1.0.0",
            versionCode = 1,
            exportTimestamp = 0L,
            dbVersion = 1
        )
        val container = BackupContainer(
            metadata = metadata,
            stocks = emptyList(),
            dividends = emptyList(),
            fireGoals = emptyList(),
            dividendIncomeRecords = emptyList(),
            transactions = emptyList(),
            achievements = emptyList(),
            livingExpenseItems = emptyList(),
            notificationRules = emptyList()
        )

        val json = gson.toJson(container)
        val restored = gson.fromJson(json, BackupContainer::class.java)

        assertThat(restored).isNotNull()
        assertThat(restored.stocks).isEmpty()
        assertThat(restored.dividends).isEmpty()
    }

    @Test
    fun `json round-trip handles special characters in strings`() {
        val container = BackupContainer(
            metadata = BackupMetadata("test", 1, 0L, 1),
            stocks = listOf(
                StockEntity(
                    code = "sh.600000",
                    name = "测试名称 with special chars: \"quotes\"",
                    marketCode = "1",
                    addedAt = 0L,
                    lastUpdated = null,
                    shares = 0,
                    yieldPeriod = "3",
                    costPerShare = 0.0
                )
            ),
            dividends = emptyList(),
            fireGoals = emptyList(),
            dividendIncomeRecords = emptyList(),
            transactions = emptyList(),
            achievements = emptyList(),
            livingExpenseItems = emptyList(),
            notificationRules = emptyList()
        )

        val json = gson.toJson(container)
        val restored = gson.fromJson(json, BackupContainer::class.java)

        assertThat(restored.stocks[0].name).isEqualTo("测试名称 with special chars: \"quotes\"")
    }

    @Test
    fun `corrupted json throws expected exception`() {
        val gson = GsonBuilder().setPrettyPrinting().create()
        try {
            gson.fromJson("{invalid json}", BackupContainer::class.java)
        } catch (e: Exception) {
            assertThat(e).isInstanceOf(com.google.gson.JsonSyntaxException::class.java)
        }
    }
}
