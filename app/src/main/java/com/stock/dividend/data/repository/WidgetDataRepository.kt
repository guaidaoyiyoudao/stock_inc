package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.PriceCacheDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.PriceCacheEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.widget.GridNextHint
import com.stock.dividend.data.widget.WidgetUiState
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Widget 数据层薄封装。
 *
 * - loadSnapshot() 只读缓存（stocks + price_cache + fire_goal + grid_plans），绝不拉网、
 *   绝不抛异常（失败返回 EMPTY）。网格下一买档用 price_cache 现价本地计算（与 App 同一
 *   [GridCalculator] 口径）——自选未持仓的网格标的也能展示。
 * - refreshPrices() 前台手动刷新：委托 [StockRepository.fetchQuotes] 拉价并写回 price_cache；
 *   拉价范围 = 持仓 ∪ 网格计划标的（修复自选股网格缓存价永不刷新的死角）。
 *
 * 不引入新的 schema，完全复用现有表。
 */
@Singleton
class WidgetDataRepository @Inject constructor(
    private val stockDao: StockDao,
    private val priceCacheDao: PriceCacheDao,
    private val fireGoalRepository: FireGoalRepository,
    private val stockRepository: StockRepository,
    private val gridPlanRepository: GridPlanRepository,
) {
    suspend fun loadSnapshot(): WidgetUiState {
        return try {
            val all = stockDao.getAll()
            val holdings = all.filter { it.shares > 0 }
            val plans = runCatching { gridPlanRepository.observeAll().first() }.getOrDefault(emptyList())
            if (holdings.isEmpty() && plans.isEmpty()) return WidgetUiState.EMPTY

            val cache = priceCacheDao.getAll().associateBy { it.code }
            val goal = fireGoalRepository.getGoalOnce()

            aggregate(
                holdings = holdings,
                cache = cache,
                fireGoalAmount = goal?.targetAmount ?: 0.0,
                gridNextHints = gridNextHints(plans, cache)
            )
        } catch (e: Exception) {
            // 读缓存失败（DB 锁/迁移中等）：吞异常返回空快照，绝不抛给 Widget 渲染层。
            WidgetUiState.EMPTY
        }
    }

    /** 前台手动刷新：拉持仓 ∪ 网格计划标的现价写回缓存。失败返回 Result.failure，由调用方标记 refreshFailed。 */
    suspend fun refreshPrices(): Result<Unit> = try {
        val all = stockDao.getAll()
        val planCodes = runCatching { gridPlanRepository.observeAll().first() }
            .getOrDefault(emptyList()).map { it.stockCode }.toSet()
        val targets = all.filter { it.shares > 0 || it.code in planCodes }
        // 委托 fetchQuotes 拉现价；其内部已写回 price_cache 并在自身异常时返回 emptyMap，
        // 故网络/解析类失败不会传到这里——这里仅捕获 DB 读取异常等。
        stockRepository.fetchQuotes(targets)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 网格下一买档（纯缓存计算）：现价来自 price_cache，最多取 2 条（Widget 空间有限）。 */
    private fun gridNextHints(
        plans: List<com.stock.dividend.data.local.entity.GridPlanEntity>,
        cache: Map<String, PriceCacheEntity>
    ): List<GridNextHint> {
        return plans.mapNotNull { plan ->
            val price = cache[plan.stockCode]?.price?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val result = GridCalculator.generate(
                basePrice = plan.basePrice,
                lowPrice = plan.lowPrice,
                highPrice = plan.highPrice,
                grids = plan.grids,
                totalCapital = plan.totalCapital,
                currentPrice = price,
                gridType = GridType.fromRaw(plan.gridType)
            )
            result.nextBuyHint?.let { next ->
                GridNextHint(
                    stockCode = plan.stockCode,
                    stockName = plan.stockName,
                    nextBuyPrice = next
                )
            }
        }.take(2)
    }

    private fun aggregate(
        holdings: List<StockEntity>,
        cache: Map<String, PriceCacheEntity>,
        fireGoalAmount: Double,
        gridNextHints: List<GridNextHint>,
    ): WidgetUiState {
        var totalMarketValue = 0.0
        var totalCost = 0.0
        var pricedCount = 0
        // lastPriceUpdatedAt 取整个 price_cache 中最新一条 updatedAt（新鲜度代理，
        // 反映"最后一次成功拉价"的时间，而非仅持仓股）。
        var maxUpdatedAt = 0L
        for (entry in cache.values) {
            if (entry.updatedAt > maxUpdatedAt) maxUpdatedAt = entry.updatedAt
        }

        for (h in holdings) {
            val price = cache[h.code]?.price
            val shares = h.shares
            if (price != null && price > 0.0) {
                totalMarketValue += price * shares
                pricedCount++
            }
            totalCost += h.costPerShare * shares
        }

        val costBasisPnl = totalMarketValue - totalCost
        val costBasisPnlPercent = if (totalCost > 0.0) costBasisPnl / totalCost else 0.0

        return WidgetUiState(
            totalMarketValue = totalMarketValue,
            pricedCount = pricedCount,
            holdingCount = holdings.size,
            costBasisPnl = costBasisPnl,
            costBasisPnlPercent = costBasisPnlPercent,
            fireGoalAmount = fireGoalAmount,
            fireProgress = if (fireGoalAmount > 0.0) (totalMarketValue / fireGoalAmount).coerceIn(0.0, 1.0) else 0.0,
            lastPriceUpdatedAt = maxUpdatedAt,
            isRefreshing = false,
            refreshFailed = false,
            gridNextHints = gridNextHints,
        )
    }
}
