package com.stock.dividend.di

import android.content.Context
import androidx.room.Room
import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.StockDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "stock_dividend.db"
        ).build()
    }

    @Provides
    fun provideStockDao(db: AppDatabase): StockDao = db.stockDao()

    @Provides
    fun provideDividendDao(db: AppDatabase): DividendDao = db.dividendDao()
}
