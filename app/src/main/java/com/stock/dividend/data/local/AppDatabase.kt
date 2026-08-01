package com.stock.dividend.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stock.dividend.data.local.dao.AchievementDao
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.FireGoalDao
import com.stock.dividend.data.local.dao.FundamentalsCacheDao
import com.stock.dividend.data.local.dao.IndustryTargetDao
import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.dao.LlmAnalysisCacheDao
import com.stock.dividend.data.local.dao.NotificationRuleDao
import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.SearchCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.StockTagDao
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.FundamentalsCacheEntity
import com.stock.dividend.data.local.entity.IndustryTargetEntity
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.local.entity.LlmAnalysisCacheEntity
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.local.entity.PriceCacheEntity
import com.stock.dividend.data.local.entity.SearchCacheEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StockTagEntity
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
        LlmAnalysisCacheEntity::class
    ],
    version = 17,
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
    }
}
