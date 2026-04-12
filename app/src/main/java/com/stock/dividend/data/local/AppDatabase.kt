package com.stock.dividend.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity

@Database(
    entities = [StockEntity::class, DividendEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun dividendDao(): DividendDao
}
