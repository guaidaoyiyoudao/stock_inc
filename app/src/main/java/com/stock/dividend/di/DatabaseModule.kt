package com.stock.dividend.di

import android.content.Context
import androidx.room.Room
import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.dao.AchievementDao
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.FireGoalDao
import com.stock.dividend.data.local.dao.IndustryTargetDao
import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.dao.NotificationRuleDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.repository.BackupRepository
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
        )
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
            .build()
    }

    @Provides
    fun provideStockDao(db: AppDatabase): StockDao = db.stockDao()

    @Provides
    fun provideDividendDao(db: AppDatabase): DividendDao = db.dividendDao()

    @Provides
    fun provideFireGoalDao(db: AppDatabase): FireGoalDao = db.fireGoalDao()

    @Provides
    fun provideDividendIncomeRecordDao(db: AppDatabase): DividendIncomeRecordDao = db.dividendIncomeRecordDao()

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideAchievementDao(db: AppDatabase): AchievementDao = db.achievementDao()

    @Provides
    fun provideLivingExpenseItemDao(db: AppDatabase): LivingExpenseItemDao = db.livingExpenseItemDao()

    @Provides
    fun provideNotificationRuleDao(db: AppDatabase): NotificationRuleDao = db.notificationRuleDao()

    @Provides
    fun provideIndustryTargetDao(db: AppDatabase): IndustryTargetDao = db.industryTargetDao()

    @Provides
    @Singleton
    fun provideBackupRepository(
        db: AppDatabase,
        stockDao: StockDao,
        dividendDao: DividendDao,
        fireGoalDao: FireGoalDao,
        dividendIncomeRecordDao: DividendIncomeRecordDao,
        transactionDao: TransactionDao,
        achievementDao: AchievementDao,
        livingExpenseItemDao: LivingExpenseItemDao,
        notificationRuleDao: NotificationRuleDao
    ): BackupRepository {
        return BackupRepository(
            db, stockDao, dividendDao, fireGoalDao,
            dividendIncomeRecordDao, transactionDao,
            achievementDao, livingExpenseItemDao, notificationRuleDao
        )
    }
}
