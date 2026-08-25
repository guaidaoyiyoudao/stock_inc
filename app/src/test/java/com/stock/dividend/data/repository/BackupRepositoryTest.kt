package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.stock.dividend.data.local.backup.BackupContainer
import com.stock.dividend.data.local.backup.BackupCounts
import com.stock.dividend.data.local.backup.BackupMetadata
import com.stock.dividend.data.local.backup.normalizeGridPlans
import com.stock.dividend.data.local.backup.normalizeStrategyPlans
import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.local.entity.TradeStrategyEntity
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
            ),
            stockTags = listOf(
                StockTagEntity(stockCode = "sh.600036", tag = "高息", createdAt = 5000L),
                StockTagEntity(stockCode = "sz.000001", tag = "白马", createdAt = 6000L)
            ),
            tradeStrategies = listOf(
                TradeStrategyEntity(
                    id = "strat1",
                    targetText = "招商银行",
                    direction = "BUY",
                    reasoning = "ROE高",
                    risks = "[\"息差收窄\"]",
                    validUntil = "2026-09-01",
                    sourceNote = "研报",
                    rawOcrText = "原文",
                    status = "ACTIVE",
                    createdAt = 7000L,
                    updatedAt = 8000L
                )
            ),
            industryTargets = listOf(
                IndustryTargetEntity(industry = "银行", targetWeight = 40.0),
                IndustryTargetEntity(industry = "电力", targetWeight = 15.0)
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

        assertThat(restored.stockTags).hasSize(2)
        assertThat(restored.stockTags.map { it.stockCode to it.tag })
            .containsExactly("sh.600036" to "高息", "sz.000001" to "白马")

        assertThat(restored.tradeStrategies).hasSize(1)
        assertThat(restored.tradeStrategies[0].id).isEqualTo("strat1")
        assertThat(restored.tradeStrategies[0].targetText).isEqualTo("招商银行")
        assertThat(restored.tradeStrategies[0].direction).isEqualTo("BUY")
        assertThat(restored.tradeStrategies[0].risks).isEqualTo("[\"息差收窄\"]")
        assertThat(restored.tradeStrategies[0].validUntil).isEqualTo("2026-09-01")

        assertThat(restored.industryTargets).hasSize(2)
        assertThat(restored.industryTargets.map { it.industry to it.targetWeight })
            .containsExactly("银行" to 40.0, "电力" to 15.0)
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

    /**
     * 旧版本（v1）导出的备份不含 industryTargets 字段。
     * Gson 绕过构造函数反序列化，缺失字段为 null；BackupRepository 用 orEmpty() 兜底，
     * 这里直接断言 null（提醒调用方必须做空安全处理）。
     */
    @Test
    fun `legacy backup without industryTargets field deserializes to null`() {
        val legacyJson = """
            {
              "metadata": {"appVersion": "1.0.0", "versionCode": 1, "exportTimestamp": 0, "dbVersion": 14},
              "stocks": [],
              "dividends": [],
              "fireGoals": [],
              "dividendIncomeRecords": [],
              "transactions": [],
              "achievements": [],
              "livingExpenseItems": [],
              "notificationRules": [],
              "stockTags": [],
              "tradeStrategies": []
            }
        """.trimIndent()

        val restored = gson.fromJson(legacyJson, BackupContainer::class.java)

        assertThat(restored).isNotNull()
        // 关键：旧备份缺失该字段 → null（非 emptyList），调用方需 orEmpty()
        assertThat(restored.industryTargets).isNull()
        // 模拟 BackupRepository 的兜底逻辑
        assertThat(restored.industryTargets.orEmpty()).isEmpty()
    }

    @Test
    fun `prefs field round-trips through json`() {
        val container = BackupContainer(
            metadata = BackupMetadata("1.0.0", 1, 0, 15),
            stocks = emptyList(),
            dividends = emptyList(),
            fireGoals = emptyList(),
            dividendIncomeRecords = emptyList(),
            transactions = emptyList(),
            achievements = emptyList(),
            livingExpenseItems = emptyList(),
            notificationRules = emptyList(),
            prefs = mapOf(
                "llm_prefs" to mapOf(
                    "llm_base_url" to "https://api.deepseek.com/v1/",
                    "llm_api_key" to "sk-xxx",
                    "llm_model" to "deepseek-chat"
                ),
                "ai_agent_prefs" to mapOf(
                    "system_prompt" to "回答加 emoji",
                    "temperature" to "0.7",
                    "max_tokens" to "2048"
                )
            )
        )
        val restored = gson.fromJson(gson.toJson(container), BackupContainer::class.java)
        assertThat(restored.prefs).hasSize(2)
        assertThat(restored.prefs["llm_prefs"]?.get("llm_api_key")).isEqualTo("sk-xxx")
        assertThat(restored.prefs["ai_agent_prefs"]?.get("system_prompt")).isEqualTo("回答加 emoji")
    }

    @Test
    fun `legacy backup without prefs field deserializes to null`() {
        // 旧版本导出的备份无 prefs 字段，恢复时 orEmpty() 兜底为空（跳过配置恢复，不崩）
        val legacyJson = """
            {
              "metadata": {"appVersion": "1.0.0", "versionCode": 1, "exportTimestamp": 0, "dbVersion": 14},
              "stocks": [], "dividends": [], "fireGoals": [], "dividendIncomeRecords": [],
              "transactions": [], "achievements": [], "livingExpenseItems": [],
              "notificationRules": [], "stockTags": [], "tradeStrategies": []
            }
        """.trimIndent()
        val restored = gson.fromJson(legacyJson, BackupContainer::class.java)
        assertThat(restored.prefs).isNull()
        assertThat(restored.prefs.orEmpty()).isEmpty()
    }

    @Test
    fun `BackupCounts settings sums all prefs entries`() {
        val counts = BackupCounts(
            settings = mapOf(
                "llm_prefs" to mapOf("a" to "1", "b" to "2"),
                "ai_agent_prefs" to mapOf("c" to "3")
            ).values.sumOf { it.size }
        )
        assertThat(counts.settings).isEqualTo(3)
        // settings 不计入 total（语义上是配置项，非业务记录）
        assertThat(counts.total).isEqualTo(0)
    }

    /** gridPlans/strategyPlans 是业务记录，须计入一句话摘要的 total。 */
    @Test
    fun `BackupCounts total includes grid and strategy plan counts`() {
        val counts = BackupCounts(stocks = 1, gridPlans = 2, strategyPlans = 3)
        assertThat(counts.total).isEqualTo(6)
        // 旧路径未传新字段时默认 0，既有摘要文本语义不变
        assertThat(BackupCounts().gridPlans).isEqualTo(0)
        assertThat(BackupCounts().strategyPlans).isEqualTo(0)
    }
    /** v20 旧备份：gridPlans 缺 notifyEnabled/gridType 字段 → Gson 得 false/null，归一化恢复默认值。 */
    @Test
    fun `normalizeGridPlans repairs legacy v20 backup fields`() {
        val json = """
              {"metadata": {"appVersion": "1.0.0", "versionCode": 1, "exportTimestamp": 0, "dbVersion": 20},
              "gridPlans": [{
                "id": "p1", "stockCode": "sh.600036", "stockName": "招商银行",
                "basePrice": 10.0, "lowPrice": 8.0, "highPrice": 12.0,
                "grids": 4, "totalCapital": 100000.0,
                "createdAt": 1, "updatedAt": 1
              }]}
        """.trimIndent()
        val container = Gson().fromJson(json, BackupContainer::class.java)
        // Gson 绕过构造函数：notifyEnabled=false、gridType=null（非空列撞 NOT NULL 的隐患）
        val plans = normalizeGridPlans(container.gridPlans, container.metadata.dbVersion)
        assertThat(plans).hasSize(1)
        assertThat(plans[0].notifyEnabled).isTrue()   // v20 无此开关，恢复默认开
        assertThat(plans[0].gridType).isEqualTo("ARITH")
    }

    /** v21 备份已含 notifyEnabled：显式 false 不被覆盖（尊重用户关闭意图），但 gridType=null 仍兜底。 */
    @Test
    fun `normalizeGridPlans keeps explicit flags from v21 but still fixes null gridType`() {
        val json = """
              {"metadata": {"appVersion": "1.0.0", "versionCode": 1, "exportTimestamp": 0, "dbVersion": 21},
              "gridPlans": [{
                "id": "p1", "stockCode": "sh.600036", "stockName": "招商银行",
                "basePrice": 10.0, "lowPrice": 8.0, "highPrice": 12.0,
                "grids": 4, "totalCapital": 100000.0,
                "notifyEnabled": false,
                "lastNotifiedLevelPrice": 9.33,
                "createdAt": 1, "updatedAt": 1
              }]}
        """.trimIndent()
        val container = Gson().fromJson(json, BackupContainer::class.java)
        val plans = normalizeGridPlans(container.gridPlans, container.metadata.dbVersion)
        assertThat(plans[0].notifyEnabled).isFalse()      // 用户显式关闭，保留
        assertThat(plans[0].lastNotifiedLevelPrice).isEqualTo(9.33)
        assertThat(plans[0].gridType).isEqualTo("ARITH")  // v21 无等比，null 兜底
    }

    /** v22 备份原样透传（含等比网格与目标股息率）。 */
    @Test
    fun `normalizeGridPlans passes through v22 backup unchanged`() {
        val plan = GridPlanEntity(
            id = "p1", stockCode = "sh.600036", stockName = "招商银行",
            basePrice = 16.0, lowPrice = 4.0, highPrice = 20.0,
            grids = 3, totalCapital = 100000.0,
            gridType = "GEOM", targetYieldPercent = 8.0, notifyEnabled = false
        )
        val plans = normalizeGridPlans(listOf(plan), dbVersion = 22)
        assertThat(plans[0]).isEqualTo(plan)
    }

    /** 自定义资金比例：合法原样透传；损坏 JSON / 档数不匹配（手改备份或旧档数残留）→ 置空回退反比默认。 */
    @Test
    fun `normalizeGridPlans keeps valid levelWeights and nulls corrupt or mismatched ones`() {
        fun plan(id: String, grids: Int, levelWeights: String?) = GridPlanEntity(
            id = id, stockCode = "sh.600036", stockName = "招商银行",
            basePrice = 10.0, lowPrice = 8.0, highPrice = 12.0, grids = grids, totalCapital = 100000.0,
            levelWeights = levelWeights
        )
        val plans = normalizeGridPlans(
            listOf(
                plan("p1", 3, "[1.0,1.0,2.0]"),   // 合法
                plan("p2", 3, "[1.0,"),            // 损坏 JSON
                plan("p3", 4, "[1.0,1.0]")         // 档数不匹配
            ),
            dbVersion = 25
        )
        assertThat(plans[0].levelWeights).isEqualTo("[1.0,1.0,2.0]")
        assertThat(plans[1].levelWeights).isNull()
        assertThat(plans[2].levelWeights).isNull()
    }

    /** v29 旧备份不可能含策略表：即使 JSON 被手改出 strategyPlans 字段也整体丢弃（防异常数据）。 */
    @Test
    fun `normalizeStrategyPlans drops strategy rows from pre-v30 backup`() {
        val plan = StrategyPlanEntity(
            id = "s1", stockCode = "sh.510880", stockName = "红利ETF"
        )
        val plans = normalizeStrategyPlans(listOf(plan), dbVersion = 29)
        assertThat(plans).isEmpty()
    }

    /** 手改/Gson 缺字段：strategyType=null（撞 NOT NULL）与非法数值（0）→ 恢复默认。 */
    @Test
    fun `normalizeStrategyPlans repairs null strategyType and invalid numbers`() {
        val json = """
              {"metadata": {"appVersion": "1.0.0", "versionCode": 1, "exportTimestamp": 0, "dbVersion": 30},
              "strategyPlans": [{
                "id": "s1", "stockCode": "sh.510880", "stockName": "红利ETF",
                "maPeriod": 0, "sellHalfPercent": 0.0, "sellAllPercent": 0.0, "dcaAmount": 0.0,
                "notifyEnabled": false,
                "createdAt": 1, "updatedAt": 1
              }]}
        """.trimIndent()
        val container = Gson().fromJson(json, BackupContainer::class.java)
        // Gson 绕过构造函数：strategyType=null（非空列撞 NOT NULL 的隐患）、数值字段全 0
        val plans = normalizeStrategyPlans(container.strategyPlans, container.metadata.dbVersion)
        assertThat(plans).hasSize(1)
        assertThat(plans[0].strategyType).isEqualTo("MA_DCA")
        assertThat(plans[0].maPeriod).isEqualTo(250)
        assertThat(plans[0].sellHalfPercent).isEqualTo(7.5)
        assertThat(plans[0].sellAllPercent).isEqualTo(15.0)
        assertThat(plans[0].dcaAmount).isEqualTo(1000.0)
        assertThat(plans[0].notifyEnabled).isFalse()   // v30 起建表即有该列且恒序列化，显式值保留
    }

    /** v31 备份的 params JSON 原样透传（新策略类型；脏 params 由 StrategyParams.decode 回退默认）。 */
    @Test
    fun `normalizeStrategyPlans passes through v31 params column`() {
        val plan = StrategyPlanEntity(
            id = "t1", stockCode = "sh.600036", stockName = "招商银行",
            strategyType = com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT,
            params = "{\"halfGainPercent\":12.0,\"allGainPercent\":30.0}"
        )
        val plans = normalizeStrategyPlans(listOf(plan), dbVersion = 31)
        assertThat(plans[0].params).isEqualTo("{\"halfGainPercent\":12.0,\"allGainPercent\":30.0}")
    }

    /** v30 备份原样透传（自定义参数与提醒状态不被动过）。 */
    @Test
    fun `normalizeStrategyPlans passes through v30 backup unchanged`() {
        val plan = StrategyPlanEntity(
            id = "s1", stockCode = "sh.510880", stockName = "红利ETF",
            maPeriod = 120, sellHalfPercent = 5.0, sellAllPercent = 10.0,
            dcaAmount = 2000.0, notifyEnabled = false, lastNotifiedSellTier = "HALF"
        )
        val plans = normalizeStrategyPlans(listOf(plan), dbVersion = 30)
        assertThat(plans[0]).isEqualTo(plan)
    }
}