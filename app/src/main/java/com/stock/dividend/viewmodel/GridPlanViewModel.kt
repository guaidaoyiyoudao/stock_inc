package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.notification.DividendAlertNotifier
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.GridAmmoSummary
import com.stock.dividend.data.repository.GridAnchor
import com.stock.dividend.data.repository.GridAnchorCalculator
import com.stock.dividend.data.repository.GridBacktestCalculator
import com.stock.dividend.data.repository.GridBacktestResult
import com.stock.dividend.data.repository.GridCalculator
import com.stock.dividend.data.repository.GridDividendOutlook
import com.stock.dividend.data.repository.GridExecution
import com.stock.dividend.data.repository.GridExecutionCalculator
import com.stock.dividend.data.repository.GridLevelFill
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.GridResult
import com.stock.dividend.data.repository.GridType
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.KlineRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 已保存的网格计划 + 其对应的当前价（用于「下一档」提示）+ 执行跟踪。
 */
@Stable
data class GridPlanItem(
    val plan: GridPlanEntity,
    val currentPrice: Double?,
    val result: GridResult,
    val execution: GridExecution,
    /** 计划过期提示：现价远高于买入起点（行情已远离当初锚定的支撑位）时非空，建议重新锚定。 */
    val stalenessHint: String? = null,
    /** 该标的当前实际持仓股数（含网格外的历史买入；网格累计买入看 execution.boughtShares）。 */
    val holdingShares: Int = 0,
    /** 股息展望：全部档位打完后的年股息与资金收益率（无分红数据为 null）。 */
    val dividendOutlook: GridDividendOutlook? = null,
    /** 逐档实际成交明细（档位价 → 汇总）；未触发档位不在内。 */
    val fillsByLevel: Map<Double, GridLevelFill> = emptyMap()
)

/** 一键重锚定确认弹窗的新旧参数对比。 */
@Stable
data class ReanchorDiff(
    val plan: GridPlanEntity,
    val newBasePrice: Double,
    val newLowPrice: Double,
    val newHighPrice: Double,
    /** 本次锚定实际采用的目标股息率（%）：计划存档值，或由现资金用完位反推。 */
    val targetYieldUsed: Double
)

@Stable
data class GridPlanUiState(
    /** 已保存的计划列表（按更新时间倒序），各含当前价提示。 */
    val items: List<GridPlanItem> = emptyList(),
    /** 用户自选股（生成器标的下拉用）。 */
    val stocks: List<StockEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** 弹药库汇总：全部计划合计总资金/已投入/剩余（无计划为 null）。 */
    val ammoSummary: GridAmmoSummary? = null,
    /** 系统通知不可用（权限被关）→ 到档提醒无法推送；null=未知/未检查。 */
    val notificationBlocked: Boolean? = null,
    // ── 生成器参数 ──
    val showGenerator: Boolean = false,
    val selectedStockCode: String = "",
    val basePriceInput: String = "",
    val lowPriceInput: String = "",
    val highPriceInput: String = "",
    val gridsInput: String = "4",
    val totalCapitalInput: String = "100000",
    /** 档位分布：等差（默认）/ 等比（百分比步长）。 */
    val gridTypeInput: GridType = GridType.ARITHMETIC,
    /** 用户目标股息率（%，到达即网格资金用完位）。 */
    val targetYieldInput: String = "6",
    /** 智能锚定结果（BOLL+目标股息率自动填充后）；null=未锚定或失败。 */
    val anchorInfo: GridAnchor? = null,
    /** 锚定按钮 loading（拉 BOLL/分红网络中）。 */
    val isAnchoring: Boolean = false,
    val anchorError: String? = null,
    /** 生成器预览结果（随参数实时重算）。 */
    val preview: GridResult? = null,
    /** 保存失败原因（参数不完整/非法时提示，避免静默无反应）。 */
    val saveError: String? = null,
    val editingId: String? = null,
    /** 编辑中的计划原 createdAt（保存时保留，避免被刷新为当前时间）。 */
    val editingCreatedAt: Long? = null,
    // ── 一键重锚定 ──
    /** 重锚定确认弹窗（新旧三价对比）；null=不显示。 */
    val reanchorDiff: ReanchorDiff? = null,
    val isReanchoring: Boolean = false,
    val reanchorError: String? = null,
    // ── 历史回测（按 plan id）──
    val backtestResults: Map<String, GridBacktestResult> = emptyMap(),
    val backtestingIds: Set<String> = emptySet(),
    val backtestErrors: Map<String, String> = emptyMap()
)

/**
 * 网格交易计划 ViewModel：列表展示 + 生成器（参数实时预览）+ 保存/删除。
 * 网格仅做计划与提示，不联网下单。
 */
@HiltViewModel
class GridPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gridPlanRepository: GridPlanRepository,
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val transactionRepository: TransactionRepository,
    private val klineRepository: KlineRepository,
    private val alertNotifier: DividendAlertNotifier
) : ViewModel() {

    /** 从个股详情页跳转时携带的 stockCode（gridPlanFor/{code} 路由参数）；全局入口为空。 */
    private val initialStockCode: String = savedStateHandle["code"] ?: ""

    private val _uiState = MutableStateFlow(GridPlanUiState())
    val uiState: StateFlow<GridPlanUiState> = _uiState.asStateFlow()

    /** code → 当前价（用于已保存计划的「下一档」提示）。 */
    private val pricesByCode = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** code → 最新年度每股现金分红（股息展望用；后台从 Room 缓存读取）。 */
    private val dpsByCode = MutableStateFlow<Map<String, Double>>(emptyMap())

    init {
        // 列表 + 自选股 + 交易流水 + 分红：combine 五流 → 带触发状态/展望/成交明细的列表项
        viewModelScope.launch {
            combine(
                gridPlanRepository.observeAll(),
                stockRepository.observeAllStocks(),
                pricesByCode,
                transactionRepository.observeAll(),
                dpsByCode
            ) { plans, stocks, prices, transactions, dps ->
                val transactionsByStock = transactions.groupBy { it.stockCode }
                GridPlanUiStateHolder(plans, stocks, prices, transactionsByStock, dps)
            }.collect { holder ->
                val (plans, stocks, prices, transactionsByStock, dps) = holder
                val holdingSharesByCode = stocks.associate { it.code to it.shares }
                val items = plans.map { plan ->
                    val price = prices[plan.stockCode]
                    val planTxs = transactionsByStock[plan.stockCode].orEmpty()
                    // 关联该股实际交易记录，标记各档位是否已触发（实际买入）
                    val result = GridCalculator.markTriggeredLevels(
                        GridCalculator.generate(
                            basePrice = plan.basePrice,
                            lowPrice = plan.lowPrice,
                            highPrice = plan.highPrice,
                            grids = plan.grids,
                            totalCapital = plan.totalCapital,
                            currentPrice = price,
                            gridType = GridType.fromRaw(plan.gridType)
                        ),
                        planTxs
                    )
                    GridPlanItem(
                        plan = plan,
                        currentPrice = price,
                        result = result,
                        execution = GridExecutionCalculator.calculate(result, plan.totalCapital, planTxs, price),
                        // 动态重锚定预警：现价远高于买入起点（行情已远离支撑位）→ 计划可能过期
                        stalenessHint = stalenessHint(price, plan.basePrice),
                        holdingShares = holdingSharesByCode[plan.stockCode] ?: 0,
                        dividendOutlook = GridCalculator.dividendOutlook(
                            result, dps[plan.stockCode], plan.totalCapital
                        ),
                        fillsByLevel = GridExecutionCalculator.levelFills(result, planTxs)
                    )
                }
                // 弹药库汇总（总资金显式传——参数非法的计划会产生 EMPTY 执行丢失资金量）
                val ammo = if (plans.isEmpty()) null else GridExecutionCalculator.summarizeAmmo(
                    totalCapitals = plans.map { it.totalCapital },
                    executions = items.map { it.execution }
                )
                _uiState.value = _uiState.value.copy(
                    items = items,
                    stocks = stocks,
                    isLoading = false,
                    ammoSummary = ammo
                )
                // 后台刷新计划涉及股票的当前价 + 分红（吞异常，§4.3）
                refreshMarketDataFor(plans.map { it.stockCode }.distinct())

                // 个股详情页入口：首次拿到自选股且 initialStockCode 命中时，
                // 自动打开生成器、预选该标的，并立即触发 BOLL+股息率智能锚定。
                if (!initialStockHandled && initialStockCode.isNotBlank() &&
                    stocks.any { it.code == initialStockCode }
                ) {
                    initialStockHandled = true
                    showGenerator()
                    onStockSelected(initialStockCode)
                    // 预填默认目标股息率后自动锚定（数据不足会静默提示，用户可改手填）
                    autoAnchor()
                }
            }
        }
        // 通知权限被系统关闭时给出可见提示（到档提醒会静默失效）
        checkNotificationStatus()
    }

    /** combine 5 流的中间数据载体（避免 Pair/Triple 嵌套）。 */
    private data class GridPlanUiStateHolder(
        val plans: List<GridPlanEntity>,
        val stocks: List<StockEntity>,
        val prices: Map<String, Double>,
        val transactionsByStock: Map<String, List<TransactionEntity>>,
        val dps: Map<String, Double>
    )

    /** 防止 initialStockCode 的自动锚定在每次自选股发射时重复触发。 */
    private var initialStockHandled = false

    /**
     * 计划过期判断：现价远高于买入起点（涨幅超过 15%）说明行情已远离当初锚定的
     * BOLL 支撑位，计划可能失真，建议重新锚定。现价低于/接近买入起点时返回 null（无需重锚）。
     */
    private fun stalenessHint(currentPrice: Double?, basePrice: Double): String? {
        if (currentPrice == null || currentPrice <= 0.0 || basePrice <= 0.0) return null
        val deviation = (currentPrice - basePrice) / basePrice * 100.0
        return if (deviation > 15.0) {
            "现价已高于买入起点 ${"%.1f".format(deviation)}%，行情偏离当初锚定，建议重新锁定"
        } else null
    }

    /** 后台刷新计划标的的当前价（网络）与最新年度每股分红（Room 缓存，股息展望用）。 */
    private fun refreshMarketDataFor(codes: List<String>) {
        if (codes.isEmpty()) return
        viewModelScope.launch {
            try {
                val stocks = stockRepository.observeAllStocksForSnapshot()
                    .filter { it.code in codes }
                if (stocks.isNotEmpty()) {
                    val quotes = stockRepository.fetchQuotes(stocks)
                    pricesByCode.value = quotes.filterValues { it > 0.0 }
                }
            } catch (_: Exception) {
                // 网络失败静默，保留空价（提示项为 null）
            }
            // 分红读 Room 缓存（DividendRepository 本地优先），失败静默
            val dps = codes.mapNotNull { code ->
                runCatching {
                    val dividends = dividendRepository.observeDividends(code).first()
                    ForecastCalculator.latestYearlyCashPerShare(dividends)
                        ?.takeIf { it > 0.0 }
                        ?.let { code to it }
                }.getOrNull()
            }.toMap()
            if (dps.isNotEmpty()) dpsByCode.value = dps
        }
    }

    /** 检查系统通知是否可用（权限被关 → 到档提醒无法推送，UI 需可见提示）。 */
    fun checkNotificationStatus() {
        viewModelScope.launch {
            val blocked = runCatching { !alertNotifier.canNotify() }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(notificationBlocked = blocked)
        }
    }

    // ── 生成器 ──────────────────────────────────────────

    fun showGenerator() {
        _uiState.value = _uiState.value.copy(
            showGenerator = true,
            editingId = null,
            editingCreatedAt = null,
            selectedStockCode = "",
            basePriceInput = "",
            lowPriceInput = "",
            highPriceInput = "",
            gridsInput = "4",
            totalCapitalInput = "100000",
            gridTypeInput = GridType.ARITHMETIC,
            targetYieldInput = "6",
            anchorInfo = null,
            isAnchoring = false,
            anchorError = null,
            saveError = null
        )
        recalculatePreview()
    }

    fun dismissGenerator() {
        _uiState.value = _uiState.value.copy(
            showGenerator = false,
            editingId = null,
            editingCreatedAt = null,
            preview = null
        )
    }

    fun onStockSelected(code: String) {
        // 选定股票后，用其当前价预填基准价
        val price = pricesByCode.value[code]
        val base = price?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
        _uiState.value = _uiState.value.copy(
            selectedStockCode = code,
            basePriceInput = _uiState.value.basePriceInput.ifBlank { base }
        )
        recalculatePreview()
    }

    fun onBasePriceChanged(v: String) = update { copy(basePriceInput = v) }
    fun onLowPriceChanged(v: String) = update { copy(lowPriceInput = v) }
    fun onHighPriceChanged(v: String) = update { copy(highPriceInput = v) }
    fun onGridsChanged(v: String) = update { copy(gridsInput = v) }
    fun onTotalCapitalChanged(v: String) = update { copy(totalCapitalInput = v) }
    fun onTargetYieldChanged(v: String) = update { copy(targetYieldInput = v) }
    fun onGridTypeChanged(v: GridType) = update { copy(gridTypeInput = v) }

    /**
     * 智能锚定：拉取选定标的的周线 BOLL + 历史分红，结合用户目标股息率
     * （= 资金用完位）自动填充基准价/上下界。网络失败或数据不全时静默提示，不崩。
     */
    fun autoAnchor() {
        val code = _uiState.value.selectedStockCode
        if (code.isBlank()) {
            _uiState.value = _uiState.value.copy(anchorError = "请先选择标的股票")
            return
        }
        val targetYield = _uiState.value.targetYieldInput.toDoubleOrNull()
        if (targetYield == null || targetYield <= 0.0) {
            _uiState.value = _uiState.value.copy(anchorError = "请输入有效的目标股息率")
            return
        }
        _uiState.value = _uiState.value.copy(isAnchoring = true, anchorError = null)
        viewModelScope.launch {
            // 并发拉日/周/月三周期 BOLL + 分红，任一失败吞异常（§4.3）
            val dailyBand = runCatching { stockRepository.fetchBoll(code, KlinePeriod.DAILY) }.getOrNull()
            val weeklyBand = runCatching { stockRepository.fetchBoll(code, KlinePeriod.WEEKLY) }.getOrNull()
            val monthlyBand = runCatching { stockRepository.fetchBoll(code, KlinePeriod.MONTHLY) }.getOrNull()
            val dividends = runCatching { dividendRepository.observeDividends(code).first() }.getOrDefault(emptyList())
            val latestDps = ForecastCalculator.latestYearlyCashPerShare(dividends)
            val anchor = if (latestDps != null) {
                // 三周期缺省时锚定内部跳过缺失周期，至少一个周期 + 分红即可
                GridAnchorCalculator.anchor(dailyBand, weeklyBand, monthlyBand, latestDps, targetYield)
            } else null

            if (anchor == null) {
                _uiState.value = _uiState.value.copy(
                    isAnchoring = false,
                    anchorError = "数据不足（需 BOLL + 历史分红），请手动填参"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isAnchoring = false,
                anchorInfo = anchor,
                anchorError = null,
                basePriceInput = String.format(java.util.Locale.US, "%.2f", anchor.basePrice),
                lowPriceInput = String.format(java.util.Locale.US, "%.2f", anchor.lowPrice),
                highPriceInput = String.format(java.util.Locale.US, "%.2f", anchor.highPrice)
            )
            recalculatePreview()
        }
    }

    private fun update(transform: GridPlanUiState.() -> GridPlanUiState) {
        _uiState.value = _uiState.value.transform().copy(saveError = null)
        recalculatePreview()
    }

    private fun recalculatePreview() {
        val s = _uiState.value
        val base = s.basePriceInput.toDoubleOrNull()
        val low = s.lowPriceInput.toDoubleOrNull()
        val high = s.highPriceInput.toDoubleOrNull()
        val grids = s.gridsInput.toIntOrNull()
        val capital = s.totalCapitalInput.toDoubleOrNull()
        if (base == null || low == null || high == null || grids == null || capital == null) {
            _uiState.value = _uiState.value.copy(preview = null)
            return
        }
        val price = pricesByCode.value[s.selectedStockCode]
        _uiState.value = _uiState.value.copy(
            preview = GridCalculator.generate(
                base, low, high, grids, capital, price, s.gridTypeInput
            )
        )
    }

    /** 保存当前生成器参数为计划（新建或覆盖编辑）。 */
    fun savePlan() {
        val s = _uiState.value
        val base = s.basePriceInput.toDoubleOrNull()
        val low = s.lowPriceInput.toDoubleOrNull()
        val high = s.highPriceInput.toDoubleOrNull()
        val grids = s.gridsInput.toIntOrNull()
        val capital = s.totalCapitalInput.toDoubleOrNull()
        // 校验失败给出可见提示，绝不静默无反应（曾因按钮可点但 savePlan 静默 return 导致「不能保存」）
        when {
            s.selectedStockCode.isBlank() ->
                return setSaveError("请先选择标的股票")
            base == null || low == null || high == null || grids == null || capital == null ->
                return setSaveError("请完整填写基准价、上下界、档数与资金")
            s.preview?.validationError != null ->
                return setSaveError("参数无效：${s.preview!!.validationError}")
        }

        val stockName = s.stocks.firstOrNull { it.code == s.selectedStockCode }?.name
            ?: s.selectedStockCode
        val now = System.currentTimeMillis()
        val plan = GridPlanEntity(
            id = s.editingId ?: UUID.randomUUID().toString(),
            stockCode = s.selectedStockCode,
            stockName = stockName,
            basePrice = base,
            lowPrice = low,
            highPrice = high,
            grids = grids,
            totalCapital = capital,
            gridType = s.gridTypeInput.raw,
            targetYieldPercent = s.targetYieldInput.toDoubleOrNull()?.takeIf { it > 0.0 },
            // 编辑时保留原创建时间；档位参数可能已变，旧的到档提醒状态作废
            createdAt = s.editingCreatedAt ?: now,
            lastNotifiedLevelPrice = null,
            updatedAt = now
        )
        viewModelScope.launch {
            gridPlanRepository.upsert(plan)
            _uiState.value = _uiState.value.copy(
                showGenerator = false,
                editingId = null,
                editingCreatedAt = null,
                preview = null
            )
        }
    }

    private fun setSaveError(message: String) {
        _uiState.value = _uiState.value.copy(saveError = message)
    }

    fun editPlan(plan: GridPlanEntity) {
        _uiState.value = _uiState.value.copy(
            showGenerator = true,
            editingId = plan.id,
            editingCreatedAt = plan.createdAt,
            selectedStockCode = plan.stockCode,
            basePriceInput = String.format(java.util.Locale.US, "%.2f", plan.basePrice),
            lowPriceInput = String.format(java.util.Locale.US, "%.2f", plan.lowPrice),
            highPriceInput = String.format(java.util.Locale.US, "%.2f", plan.highPrice),
            gridsInput = plan.grids.toString(),
            totalCapitalInput = String.format(java.util.Locale.US, "%.0f", plan.totalCapital),
            gridTypeInput = GridType.fromRaw(plan.gridType)
        )
        recalculatePreview()
    }

    fun deletePlan(id: String) {
        viewModelScope.launch { gridPlanRepository.delete(id) }
    }

    /** 切换「到档提醒」开关（关=价格到达下一买入档时不再推送通知）。 */
    fun toggleNotify(plan: GridPlanEntity) {
        viewModelScope.launch {
            runCatching {
                // 只翻转开关：不动 updatedAt，避免开关操作把计划顶到列表顶部
                gridPlanRepository.upsert(plan.copy(notifyEnabled = !plan.notifyEnabled))
            }
            // 开关意义与通知权限强相关，顺手刷新一次状态提示
            checkNotificationStatus()
        }
    }

    // ── 一键重锚定（重锚定预警的执行闭环）──────────────

    /**
     * 一键重锚定：重拉该标的三周期 BOLL + 分红，按计划的目标股息率
     * （存档值，缺失时由现资金用完位反推）重算三价，产出新旧对比待确认。
     */
    fun reanchorPlan(plan: GridPlanEntity) {
        _uiState.value = _uiState.value.copy(isReanchoring = true, reanchorError = null)
        viewModelScope.launch {
            val dailyBand = runCatching { stockRepository.fetchBoll(plan.stockCode, KlinePeriod.DAILY) }.getOrNull()
            val weeklyBand = runCatching { stockRepository.fetchBoll(plan.stockCode, KlinePeriod.WEEKLY) }.getOrNull()
            val monthlyBand = runCatching { stockRepository.fetchBoll(plan.stockCode, KlinePeriod.MONTHLY) }.getOrNull()
            val dividends = runCatching {
                dividendRepository.observeDividends(plan.stockCode).first()
            }.getOrDefault(emptyList())
            val dps = ForecastCalculator.latestYearlyCashPerShare(dividends)

            // 目标股息率：优先计划存档值（用户当初意图）；旧数据/手填没有 → 由现资金用完位反推
            val targetYield = plan.targetYieldPercent
                ?: dps?.takeIf { it > 0.0 && plan.lowPrice > 0.0 }?.let { it / plan.lowPrice * 100.0 }
            val anchor = if (dps != null && targetYield != null && targetYield > 0.0) {
                GridAnchorCalculator.anchor(dailyBand, weeklyBand, monthlyBand, dps, targetYield)
            } else null

            if (anchor == null || targetYield == null) {
                _uiState.value = _uiState.value.copy(
                    isReanchoring = false,
                    reanchorError = "数据不足（需 BOLL + 历史分红），无法重锚定"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isReanchoring = false,
                reanchorDiff = ReanchorDiff(
                    plan = plan,
                    newBasePrice = anchor.basePrice,
                    newLowPrice = anchor.lowPrice,
                    newHighPrice = anchor.highPrice,
                    targetYieldUsed = targetYield
                )
            )
        }
    }

    /** 确认重锚定：保存新三价（保留创建时间），重置到档提醒状态。 */
    fun confirmReanchor() {
        val diff = _uiState.value.reanchorDiff ?: return
        viewModelScope.launch {
            runCatching {
                gridPlanRepository.upsert(
                    diff.plan.copy(
                        basePrice = diff.newBasePrice,
                        lowPrice = diff.newLowPrice,
                        highPrice = diff.newHighPrice,
                        targetYieldPercent = diff.targetYieldUsed,
                        lastNotifiedLevelPrice = null,  // 档位变了，旧提醒状态作废
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            _uiState.value = _uiState.value.copy(reanchorDiff = null, reanchorError = null)
        }
    }

    fun dismissReanchor() {
        _uiState.value = _uiState.value.copy(reanchorDiff = null, reanchorError = null)
    }

    // ── 历史回测（按需触发，避免全列表自动拉 K 线）──────

    /**
     * 回测单个计划：拉近 250 个交易日日线（单次请求），还原「这段行情里这套网格的表现」。
     * 结果/加载中/失败按 plan id 存储；口径为收盘价 + 档位价成交假设（UI 已声明）。
     */
    fun backtestPlan(plan: GridPlanEntity) {
        if (plan.id in _uiState.value.backtestingIds) return
        _uiState.value = _uiState.value.copy(
            backtestingIds = _uiState.value.backtestingIds + plan.id,
            backtestErrors = _uiState.value.backtestErrors - plan.id
        )
        viewModelScope.launch {
            val klines = runCatching {
                klineRepository.fetchKlines(plan.stockCode, KlinePeriod.DAILY, 250)
            }.getOrDefault(emptyList())
            val result = GridBacktestCalculator.backtest(
                klines = klines,
                basePrice = plan.basePrice,
                lowPrice = plan.lowPrice,
                highPrice = plan.highPrice,
                grids = plan.grids,
                totalCapital = plan.totalCapital,
                gridType = GridType.fromRaw(plan.gridType)
            )
            _uiState.value = _uiState.value.copy(
                backtestingIds = _uiState.value.backtestingIds - plan.id,
                backtestResults = if (result != null) {
                    _uiState.value.backtestResults + (plan.id to result)
                } else {
                    _uiState.value.backtestResults
                },
                backtestErrors = if (result == null) {
                    _uiState.value.backtestErrors + (plan.id to "K 线数据不足，无法回测")
                } else {
                    _uiState.value.backtestErrors
                }
            )
        }
    }
}
