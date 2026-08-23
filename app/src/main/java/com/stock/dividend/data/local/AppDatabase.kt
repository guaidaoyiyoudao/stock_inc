package com.stock.dividend.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stock.dividend.data.local.dao.AchievementDao
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.ErrorLogDao
import com.stock.dividend.data.local.dao.FinancialStatementsCacheDao
import com.stock.dividend.data.local.dao.FireGoalDao
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.dao.GridPlanDao
import com.stock.dividend.data.local.dao.IndustryTargetDao
import com.stock.dividend.data.local.dao.KlineCacheDao
import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.dao.NotificationRuleDao
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.SearchCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.StockTagDao
import com.stock.dividend.data.local.dao.StrategyPlanDao
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.ErrorLogEntity
import com.stock.dividend.data.local.entity.FuyaoCacheEntity
import com.stock.dividend.data.local.entity.FinancialStatementsCacheEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import com.stock.dividend.data.local.entity.KlineCacheEntity
import com.stock.dividend.data.local.entity.KlineCacheMetaEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.PriceCacheEntity
import com.stock.dividend.data.local.entity.SearchCacheEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StockTagEntity
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.local.entity.TransactionEntity

@Database(
    entities = [
        StockEntity::class,
        DividendEntity::class,
        FireGoalEntity::class,
        DividendIncomeRecordEntity::class,
        TransactionEntity::class,
        AchievementEntity::class,
        LivingExpenseItemEntity::class,
        NotificationRuleEntity::class,
        IndustryTargetEntity::class,
        PriceCacheEntity::class,
        SearchCacheEntity::class,
        StockTagEntity::class,
        TradeStrategyEntity::class,
        FundamentalsCacheEntity::class,
        LlmAnalysisCacheEntity::class,
        FinancialStatementsCacheEntity::class,
        GridPlanEntity::class,
        KlineCacheEntity::class,
        KlineCacheMetaEntity::class,
        ErrorLogEntity::class,
        FuyaoCacheEntity::class,
        StrategyPlanEntity::class
    ],
    version = 30,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun dividendDao(): DividendDao
    abstract fun fireGoalDao(): FireGoalDao
    abstract fun dividendIncomeRecordDao(): DividendIncomeRecordDao
    abstract fun transactionDao(): TransactionDao
    abstract fun achievementDao(): AchievementDao
    abstract fun livingExpenseItemDao(): LivingExpenseItemDao
    abstract fun notificationRuleDao(): NotificationRuleDao
    abstract fun industryTargetDao(): IndustryTargetDao
    abstract fun priceCacheDao(): PriceCacheDao
    abstract fun searchCacheDao(): SearchCacheDao
    abstract fun stockTagDao(): StockTagDao
    abstract fun tradeStrategyDao(): TradeStrategyDao
    abstract fun fundamentalsCacheDao(): FundamentalsCacheDao
    abstract fun llmAnalysisCacheDao(): LlmAnalysisCacheDao
    abstract fun financialStatementsCacheDao(): FinancialStatementsCacheDao
    abstract fun gridPlanDao(): GridPlanDao
    abstract fun klineCacheDao(): KlineCacheDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun fuyaoCacheDao(): com.stock.dividend.data.local.dao.FuyaoCacheDao
    abstract fun strategyPlanDao(): StrategyPlanDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stocks ADD COLUMN shares INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stocks ADD COLUMN yieldPeriod TEXT NOT NULL DEFAULT '3'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fire_goal` (" +
                            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "`targetAmount` REAL NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stocks ADD COLUMN costPerShare REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dividend_income_records` (" +
                            "`id` TEXT NOT NULL PRIMARY KEY, " +
                            "`stockCode` TEXT NULLABLE, " +
                            "`year` INTEGER NOT NULL, " +
                            "`date` TEXT NOT NULL, " +
                            "`amount` REAL NOT NULL, " +
                            "`exDividendDate` TEXT NULLABLE, " +
                            "`source` TEXT NOT NULL DEFAULT 'auto', " +
                            "`note` TEXT NULLABLE, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`stockCode`) REFERENCES `stocks`(`code`) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dividend_income_records_stock_code` ON `dividend_income_records`(`stockCode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dividend_income_records_year` ON `dividend_income_records`(`year`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transactions` (" +
                            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "`stockCode` TEXT NOT NULL, " +
                            "`type` TEXT NOT NULL, " +
                            "`shares` INTEGER NOT NULL, " +
                            "`price` REAL NOT NULL DEFAULT 0.0, " +
                            "`date` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`stockCode`) REFERENCES `stocks`(`code`) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_stock_code` ON `transactions`(`stockCode`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `achievements` (" +
                            "`id` TEXT NOT NULL PRIMARY KEY, " +
                            "`unlockedAt` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `living_expense_items` (" +
                            "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "`name` TEXT NOT NULL, " +
                            "`amount` REAL NOT NULL, " +
                            "`period` TEXT NOT NULL, " +
                            "`sortOrder` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_living_expense_items_sortOrder` " +
                            "ON `living_expense_items`(`sortOrder`)"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dividends ADD COLUMN planNoticeDate TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notification_rules` (" +
                            "`id` TEXT NOT NULL PRIMARY KEY, " +
                            "`type` TEXT NOT NULL, " +
                            "`stockCode` TEXT, " +
                            "`enabled` INTEGER NOT NULL, " +
                            "`thresholdPercent` REAL NOT NULL, " +
                            "`lastWasAboveThreshold` INTEGER, " +
                            "`lastCheckedAt` INTEGER, " +
                            "`lastTriggeredAt` INTEGER, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_rules_type_stockCode` " +
                            "ON `notification_rules`(`type`, `stockCode`)"
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stocks ADD COLUMN targetWeight REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 新增 industry 列
                db.execSQL("ALTER TABLE stocks ADD COLUMN industry TEXT NOT NULL DEFAULT ''")
                // 个股 targetWeight 语义从「占总资产%」改为「占其行业%」，清零旧值让用户在新模型下重设
                db.execSQL("UPDATE stocks SET targetWeight = 0.0")
                // 行业目标配比表
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS industry_targets (" +
                            "industry TEXT NOT NULL PRIMARY KEY, " +
                            "targetWeight REAL NOT NULL DEFAULT 0.0" +
                            ")"
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 实时价格缓存（永久缓存 + 后台刷新）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_cache` (" +
                            "`code` TEXT NOT NULL PRIMARY KEY, " +
                            "`price` REAL NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)"
                )
                // 搜索结果缓存
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_cache` (" +
                            "`code` TEXT NOT NULL PRIMARY KEY, " +
                            "`queryKey` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`marketCode` TEXT NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_search_cache_queryKey` " +
                            "ON `search_cache`(`queryKey`)"
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 多对多股票标签表（每只股可贴多个标签，标签可被多只股共享）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stock_tags` (" +
                            "`stockCode` TEXT NOT NULL, " +
                            "`tag` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`stockCode`, `tag`), " +
                            "FOREIGN KEY(`stockCode`) REFERENCES `stocks`(`code`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stock_tags_stockCode` " +
                            "ON `stock_tags`(`stockCode`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stock_tags_tag` " +
                            "ON `stock_tags`(`tag`)"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 买入阈值倍数：股息率达到「10Y 国债 × 该倍数」时提示买入
                db.execSQL(
                    "ALTER TABLE stocks ADD COLUMN buyThresholdMultiplier REAL NOT NULL DEFAULT 2.5"
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 截图策略分析产出的全局买卖策略（不绑定个股，无 stockCode）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trade_strategies` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, " +
                        "`targetText` TEXT NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`reasoning` TEXT NOT NULL, " +
                        "`risks` TEXT NOT NULL, " +
                        "`validUntil` TEXT, " +
                        "`sourceNote` TEXT, " +
                        "`rawOcrText` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 基本面缓存：季报级慢变数据，7 天 TTL
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fundamentals_cache` (" +
                        "`stockCode` TEXT NOT NULL PRIMARY KEY, " +
                        "`payload` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL)"
                )
                // LLM 解读结果缓存：prompt 哈希 key，24h TTL
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `llm_analysis_cache` (" +
                        "`cacheKey` TEXT NOT NULL PRIMARY KEY, " +
                        "`scope` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 财务三表缓存：季报级慢变数据，7 天 TTL
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `financial_statements_cache` (" +
                        "`stockCode` TEXT NOT NULL PRIMARY KEY, " +
                        "`payload` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 交易备注/复盘笔记：用户自由填写，可空
                db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 网格交易计划：用户为某标的设定的网格参数（仅计划/提示，不联网下单）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `grid_plans` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, " +
                        "`stockCode` TEXT NOT NULL, " +
                        "`stockName` TEXT NOT NULL, " +
                        "`basePrice` REAL NOT NULL, " +
                        "`lowPrice` REAL NOT NULL, " +
                        "`highPrice` REAL NOT NULL, " +
                        "`grids` INTEGER NOT NULL, " +
                        "`totalCapital` REAL NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`stockCode`) REFERENCES `stocks`(`code`) ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_grid_plans_stockCode` ON `grid_plans`(`stockCode`)"
                )
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 网格到档提醒：开关（默认开）+ 上次已提醒档位价（每档只提醒一次的去重状态）
                db.execSQL(
                    "ALTER TABLE grid_plans ADD COLUMN notifyEnabled INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL("ALTER TABLE grid_plans ADD COLUMN lastNotifiedLevelPrice REAL")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 等比网格选项 + 重锚定用的目标股息率（用户意图，可空——旧数据/手填参数无此值）
                db.execSQL(
                    "ALTER TABLE grid_plans ADD COLUMN gridType TEXT NOT NULL DEFAULT 'ARITH'"
                )
                db.execSQL("ALTER TABLE grid_plans ADD COLUMN targetYieldPercent REAL")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 按股息率网格（YIELD）：档位价换算基准的年度每股分红快照（可空——仅 YIELD 计划填充）
                db.execSQL("ALTER TABLE grid_plans ADD COLUMN dpsPerShare REAL")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // K 线本地缓存（历史不可变数据持久化：离线可用 + 增量刷新）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kline_cache` (" +
                        "`stockCode` TEXT NOT NULL, `period` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                        "`open` REAL NOT NULL, `high` REAL NOT NULL, `low` REAL NOT NULL, " +
                        "`close` REAL NOT NULL, `volume` REAL NOT NULL, " +
                        "PRIMARY KEY(`stockCode`, `period`, `date`))"
                )
                // 每股每周期同步状态：fetchedAt + 写入时最新除权日（前复权漂移检测）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kline_cache_meta` (" +
                        "`stockCode` TEXT NOT NULL, `period` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, `lastExDividendDate` TEXT, " +
                        "PRIMARY KEY(`stockCode`, `period`))"
                )
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 网格自定义档位资金比例（JSON 数组字符串；null = 默认 1/price 反比分配）
                db.execSQL("ALTER TABLE grid_plans ADD COLUMN levelWeights TEXT")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 关键失败日志（数据获取失败等静默失败的持久化记录，设置 → 数据 → 失败日志）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `error_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`message` TEXT NOT NULL, " +
                        "`detail` TEXT)"
                )
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // K 线缓存来源标记：同花顺/腾讯前复权基准不同，换源须全量重建（KlineRepository）
                db.execSQL(
                    "ALTER TABLE kline_cache_meta ADD COLUMN source TEXT NOT NULL DEFAULT 'tencent'"
                )
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 扶摇数据持久缓存（交易日历/指数日K/基金持仓等不可变历史的离线可用，§4.2A）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fuyao_cache` (" +
                        "`key` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 网格波段模式（股息率卖出锚 + 底仓/波段拆分 + 弹药回流，2026-08-23）：
                // swingMode 波段开关（默认关 = 纯买入，旧行为不变）、swingStepPercent 波段步长
                // （股息率百分点，null = 回落一档）、swingRatioPercent 波段仓位比例（默认 30，
                // 其余为底仓只买不卖）、lastNotifiedSellLevelPrice 卖出档提醒去重状态
                db.execSQL(
                    "ALTER TABLE grid_plans ADD COLUMN swingMode INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE grid_plans ADD COLUMN swingStepPercent REAL")
                db.execSQL(
                    "ALTER TABLE grid_plans ADD COLUMN swingRatioPercent REAL NOT NULL DEFAULT 30"
                )
                db.execSQL("ALTER TABLE grid_plans ADD COLUMN lastNotifiedSellLevelPrice REAL")
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 交易策略计划（首版：MA_DCA 年线定投——250 日线下定投买入，
                // 高于年线 7.5% 卖一半 / 15% 全卖；仅提示不下单）。
                // notifyEnabled 卖出阈值推送开关（默认开）、lastNotifiedSellTier
                // 卖出档边沿触发去重状态（HALF/ALL，偏离回落清空，仅通知回写不动 updatedAt）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `strategy_plans` (" +
                            "`id` TEXT NOT NULL PRIMARY KEY, " +
                            "`stockCode` TEXT NOT NULL, " +
                            "`stockName` TEXT NOT NULL, " +
                            "`strategyType` TEXT NOT NULL DEFAULT 'MA_DCA', " +
                            "`maPeriod` INTEGER NOT NULL DEFAULT 250, " +
                            "`sellHalfPercent` REAL NOT NULL DEFAULT 7.5, " +
                            "`sellAllPercent` REAL NOT NULL DEFAULT 15, " +
                            "`dcaAmount` REAL NOT NULL DEFAULT 1000, " +
                            "`note` TEXT, " +
                            "`notifyEnabled` INTEGER NOT NULL DEFAULT 1, " +
                            "`lastNotifiedSellTier` TEXT, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`stockCode`) REFERENCES `stocks`(`code`) ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_strategy_plans_stockCode` " +
                            "ON `strategy_plans`(`stockCode`)"
                )
            }
        }
    }
}
