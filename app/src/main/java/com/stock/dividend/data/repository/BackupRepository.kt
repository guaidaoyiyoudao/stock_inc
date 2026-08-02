package com.stock.dividend.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.stock.dividend.data.local.AppDatabase
import com.stock.dividend.data.local.backup.BackupContainer
import com.stock.dividend.data.local.backup.BackupCounts
import com.stock.dividend.data.local.backup.BackupMetadata
import com.stock.dividend.data.local.backup.BackupSummary
import com.stock.dividend.data.local.dao.AchievementDao
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.FireGoalDao
import com.stock.dividend.data.local.dao.IndustryTargetDao
import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.dao.NotificationRuleDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.StockTagDao
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.dao.TransactionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    private val db: AppDatabase,
    private val stockDao: StockDao,
    private val dividendDao: DividendDao,
    private val fireGoalDao: FireGoalDao,
    private val dividendIncomeRecordDao: DividendIncomeRecordDao,
    private val transactionDao: TransactionDao,
    private val achievementDao: AchievementDao,
    private val livingExpenseItemDao: LivingExpenseItemDao,
    private val notificationRuleDao: NotificationRuleDao,
    private val stockTagDao: StockTagDao,
    private val tradeStrategyDao: TradeStrategyDao,
    private val industryTargetDao: IndustryTargetDao
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun exportToJson(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val container = coroutineScope {
                val stocks = async { stockDao.getAll() }
                val dividends = async { dividendDao.getAll() }
                val fireGoals = async { fireGoalDao.getAll() }
                val incomeRecords = async { dividendIncomeRecordDao.getAllRecords() }
                val transactions = async { transactionDao.getAll() }
                val achievements = async { achievementDao.getAll() }
                val expenses = async { livingExpenseItemDao.getAllOnce() }
                val rules = async { notificationRuleDao.getAll() }
                val stockTags = async { stockTagDao.getAll() }
                val tradeStrategies = async { tradeStrategyDao.getAllForBackup() }
                val industryTargets = async { industryTargetDao.getAll() }

                BackupContainer(
                    metadata = BackupMetadata(
                        appVersion = try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                        } catch (_: PackageManager.NameNotFoundException) {
                            "unknown"
                        },
                        versionCode = try {
                            @Suppress("DEPRECATION")
                            val code = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                            code
                        } catch (_: PackageManager.NameNotFoundException) {
                            0
                        },
                        exportTimestamp = System.currentTimeMillis(),
                        dbVersion = db.openHelper.readableDatabase.version
                    ),
                    stocks = stocks.await(),
                    dividends = dividends.await(),
                    fireGoals = fireGoals.await(),
                    dividendIncomeRecords = incomeRecords.await(),
                    transactions = transactions.await(),
                    achievements = achievements.await(),
                    livingExpenseItems = expenses.await(),
                    notificationRules = rules.await(),
                    stockTags = stockTags.await(),
                    tradeStrategies = tradeStrategies.await(),
                    industryTargets = industryTargets.await()
                )
            }

            val json = gson.toJson(container)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toByteArray(Charsets.UTF_8))
            } ?: return@withContext Result.failure(IOException("无法创建文件"))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importFromJson(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext Result.failure(IOException("无法读取文件"))

            val container = try {
                gson.fromJson(json, BackupContainer::class.java)
            } catch (e: JsonSyntaxException) {
                return@withContext Result.failure(IllegalArgumentException("无效的备份文件格式"))
            }

            db.withTransaction {
                // Delete children first (foreign key safety)
                stockTagDao.deleteAll()
                tradeStrategyDao.clear()
                dividendIncomeRecordDao.deleteAll()
                dividendDao.deleteAll()
                transactionDao.deleteAll()
                notificationRuleDao.deleteAll()
                achievementDao.deleteAll()
                livingExpenseItemDao.deleteAll()
                fireGoalDao.delete()
                stockDao.deleteAll()
                // industry_targets 无 FK，清空安全
                industryTargetDao.clear()

                // Insert parents first, then children
                stockDao.insertAll(container.stocks)
                fireGoalDao.insertAll(container.fireGoals)
                livingExpenseItemDao.insertAll(container.livingExpenseItems)
                achievementDao.replaceAll(container.achievements)
                notificationRuleDao.insertAll(container.notificationRules)
                dividendDao.insertAll(container.dividends)
                dividendIncomeRecordDao.insertAll(container.dividendIncomeRecords)
                transactionDao.insertAll(container.transactions)
                // stock_tags 必须在 stocks 之后（FK），IGNORE 防御重复主键
                stockTagDao.insertAll(container.stockTags)
                tradeStrategyDao.insertAll(container.tradeStrategies)
                // 旧备份可能不含 industryTargets 字段（Gson 绕过构造函数 → null），orEmpty 兜底
                industryTargetDao.insertAll(container.industryTargets.orEmpty())

                // 交易记录已全部回灌，按移动加权平均重算每只股票的持仓量与成本，
                // 确保旧备份恢复后立即使用统一算法（避免沿用快照里的旧算法值）。
                container.stocks.forEach { stock ->
                    val holding = HoldingCalculator.calculate(transactionDao.getByStock(stock.code))
                    stockDao.updateShares(stock.code, holding.totalShares)
                    stockDao.updateCostPerShare(stock.code, holding.avgCostPerShare)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateBackup(context: Context, uri: Uri): Result<BackupSummary> = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext Result.failure(IOException("无法读取文件"))

            val container = try {
                gson.fromJson(json, BackupContainer::class.java)
            } catch (e: JsonSyntaxException) {
                return@withContext Result.failure(IllegalArgumentException("无效的备份文件格式"))
            }

            // container 本身或字段可能因旧备份缺失而被 Gson 置 null，统一 orEmpty 兜底
            val safeContainer = container ?: return@withContext Result.failure(
                IllegalArgumentException("无效的备份文件格式")
            )
            val counts = BackupCounts(
                stocks = safeContainer.stocks.orEmpty().size,
                dividends = safeContainer.dividends.orEmpty().size,
                transactions = safeContainer.transactions.orEmpty().size,
                dividendIncomeRecords = safeContainer.dividendIncomeRecords.orEmpty().size,
                tradeStrategies = safeContainer.tradeStrategies.orEmpty().size,
                industryTargets = safeContainer.industryTargets.orEmpty().size
            )
            Result.success(BackupSummary(metadata = safeContainer.metadata, counts = counts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
