package com.stock.dividend.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stock.dividend.data.local.dao.AchievementDao
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.FireGoalDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.AchievementEntity
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.FireGoalEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity

@Database(
    entities = [StockEntity::class, DividendEntity::class, FireGoalEntity::class, DividendIncomeRecordEntity::class, TransactionEntity::class, AchievementEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun dividendDao(): DividendDao
    abstract fun fireGoalDao(): FireGoalDao
    abstract fun dividendIncomeRecordDao(): DividendIncomeRecordDao
    abstract fun transactionDao(): TransactionDao
    abstract fun achievementDao(): AchievementDao

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
                db.execSQL(
                    "INSERT INTO transactions (stockCode, type, shares, price, date, createdAt) " +
                            "SELECT code, 'BUY', shares, costPerShare, date(addedAt / 1000, 'unixepoch'), addedAt " +
                            "FROM stocks WHERE shares > 0"
                )
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
    }
}
