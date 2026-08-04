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
    private val industryTargetDao: IndustryTargetDao,
    private val gridPlanDao: com.stock.dividend.data.local.dao.GridPlanDao
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * 需要备份的 SharedPreferences 文件名清单（真正的用户配置，不含可重建的缓存）。
     * - llm_prefs：LLM 端点（baseUrl/apiKey/model）
     * - ai_agent_prefs：AI 助手设置（系统提示词/温度/maxTokens）
     */
    private val backedUpPrefsFiles = listOf("llm_prefs", "ai_agent_prefs")

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
                val gridPlans = async { gridPlanDao.getAllForBackup() }

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
                    industryTargets = industryTargets.await(),
                    gridPlans = gridPlans.await(),
                    prefs = readPrefsForBackup(context)
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
                gridPlanDao.clear()
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
                // grid_plans v20 起新增，旧备份无此字段 → null → orEmpty 兜底
                gridPlanDao.insertAll(container.gridPlans.orEmpty())

                // 交易记录已全部回灌，按移动加权平均重算每只股票的持仓量与成本，
                // 确保旧备份恢复后立即使用统一算法（避免沿用快照里的旧算法值）。
                container.stocks.forEach { stock ->
                    val holding = HoldingCalculator.calculate(transactionDao.getByStock(stock.code))
                    stockDao.updateShares(stock.code, holding.totalShares)
                    stockDao.updateCostPerShare(stock.code, holding.avgCostPerShare)
                }
            }

            // 恢复用户配置（SharedPreferences，非 Room，在事务外）。
            // 旧备份可能不含 prefs 字段（Gson → null），orEmpty 兜底为空即跳过。
            restorePrefs(context, container.prefs.orEmpty())

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
                industryTargets = safeContainer.industryTargets.orEmpty().size,
                settings = safeContainer.prefs.orEmpty().values.sumOf { it.size }
            )
            Result.success(BackupSummary(metadata = safeContainer.metadata, counts = counts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 读取所有需备份的 SharedPreferences 文件 → Map<文件名, Map<key, value>>。 */
    private fun readPrefsForBackup(context: Context): Map<String, Map<String, Any?>> =
        backedUpPrefsFiles.mapNotNull { name ->
            val all = runCatching {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            }.getOrDefault(emptyMap())
            if (all.isEmpty()) null else name to all
        }.toMap()

    /**
     * 把备份的 SharedPreferences 写回设备。仅恢复在 [backedUpPrefsFiles] 清单内的文件，
     * 避免备份文件被篡改后注入任意 prefs。按值运行时类型选对应 putXxx，
     * 兼容 Gson 反序列化后 Number/String/Boolean/Collection 的实际类型。
     */
    private fun restorePrefs(context: Context, prefs: Map<String, Map<String, Any?>>) {
        backedUpPrefsFiles.forEach { name ->
            val entries = prefs[name] ?: return@forEach
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            entries.forEach { (key, value) -> applyTyped(editor, key, value) }
            editor.apply()
        }
    }

    /** 按值运行时类型选对应 putXxx；Double 无原生存储，转 String（读取处用 toDoubleOrNull）。 */
    private fun applyTyped(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: Any?
    ) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Number -> editor.putString(key, value.toString())
            is String -> editor.putString(key, value)
            is Collection<*> -> editor.putStringSet(key, value.mapNotNull { it?.toString() }.toSet())
            null -> editor.remove(key)
            else -> editor.putString(key, value.toString())
        }
    }
}
