package com.stock.dividend.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity

@Database(
    entities = [StockEntity::class, DividendEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun dividendDao(): DividendDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stocks ADD COLUMN shares INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stocks ADD COLUMN yieldPeriod TEXT NOT NULL DEFAULT '3'")
            }
        }
    }
}
