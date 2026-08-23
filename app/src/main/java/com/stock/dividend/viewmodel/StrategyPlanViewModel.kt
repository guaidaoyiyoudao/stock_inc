package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.notification.DividendAlertNotifier
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.HoldingCalculator
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.MaDcaEvaluation
import com.stock.dividend.data.repository.MaDcaSignal
import com.stock.dividend.data.repository.MaDcaStrategyCalculator
import com.stock.dividend.data.repository.StrategyPlanRepository
import com.stock.dividend.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 已保存的策略计划 + 当前评估（年线/偏离度/信号）+ 派生操作数量。
 * [evaluation] 为 null 表示数据不足（上市不足均线周期/无现价/日线拉取失败）。
 */
@Stable
data class StrategyPlanItem(
    val plan: StrategyPlanEntity,
    val currentPrice: Double?,
    val evaluation: MaDcaEvaluation?,
    /** 该标的当前持仓股数（交易流水摊薄口径）。 */
    val holdingShares: Int,
    /** 当前信号建议卖出股数（整手折算；非卖出信号为 0）。 */
    val sellTargetShares: Int,
    /** 定投窗口一键记账预填股数（定投金额按现价折整手；无窗口/不足一手为 0）。 */
    val dcaBuyShares: Int
)

@Stable
data class StrategyPlanUiState(
    val items: List<StrategyPlanItem> = emptyList(),
    /** 用户自选股（编辑器标的搜索用）。 */
    val stocks: List<StockEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** 系统通知不可用（权限被关）→ 卖出阈值提醒无法推送；null=未知/未检查。 */
    val notificationBlocked: Boolean? = null,
    // ── 编辑器（BottomSheet）──
    val showEditor: Boolean = false,
    val selectedStockCode: String = "",
    val maPeriodInput: String = "250",
    val sellHalfInput: String = "7.5",
    val sellAllInput: String = "15",
    val dcaAmountInput: String = "1000",
    val noteInput: String = "",
    val notifyEnabledInput: Boolean = true,
    /** 编辑器实时预览（已选标的且有日线数据时非空）：年线值/触发价/当前信号。 */
    val previewEvaluation: MaDcaEvaluation? = null,
    /** 保存失败原因（参数非法/未选标的时提示，避免静默无反应）。 */
    val saveError: String? = null,
    val editingId: String? = null,
    /** 编辑中的计划原 createdAt（保存时保留，避免被刷新为当前时间）。 */
    val editingCreatedAt: Long? = null,
    /** 删除确认弹窗中的计划；null=不显示。 */
    val deletingPlan: StrategyPlanEntity? = null
)

/**
 * 交易策略 ViewModel：列表展示（年线定投评估）+ 编辑器（参数实时预览）+ 保存/删除。
 * 策略仅做信号提示与记账辅助，不联网下单。
 */
@HiltViewModel
class StrategyPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val strategyPlanRepository: StrategyPlanRepository,
    private val marketDataPlane: MarketDataPlane,
    private val transactionRepository: TransactionRepository,
    private val alertNotifier: DividendAlertNotifier
) : ViewModel() {

    /** 从个股详情/今日页跳转时携带的 stockCode（strategyPlanFor/{code}）；全局入口为空。 */
    private val initialStockCode: String = savedStateHandle["code"] ?: ""

    private val _uiState = MutableStateFlow(StrategyPlanUiState())
    val uiState = _uiState.asStateFlow()

    /** code → 当前价（计划列表提示用）。 */
    private val pricesByCode = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** code → 日线收盘价（年线计算用；后台按各计划最大周期拉取）。 */
    private val closesByCode = MutableStateFlow<Map<String, List<Double>>>(emptyMap())

    /** closesByCode 的最近快照（编辑器预览即时重算用，避免等流重发射）。 */
    @Volatile private var lastCloses: Map<String, List<Double>> = emptyMap()

    /** 防止 initialStockCode 的自动打开编辑器在每次自选股发射时重复触发。 */
    private var initialStockHandled = false

    init {
        // 列表 + 自选股 + 现价 + 收盘价 + 交易流水：combine 五流 → 带评估的列表项
        viewModelScope.launch {
            combine(
                strategyPlanRepository.observeAll(),
                marketDataPlane.observeAllStocks(),
                pricesByCode,
                closesByCode,
                transactionRepository.observeAll()
            ) { plans, stocks, prices, closes, transactions ->
                StrategyUiStateHolder(plans, stocks, prices, closes, transactions)
            }.collect { holder ->
                val (plans, stocks, prices, closes, transactions) = holder
                val holdingsByCode = transactions
                    .groupBy { it.stockCode }
                    .mapValues { (_, list) -> HoldingCalculator.calculate(list).totalShares }
                val items = plans.map { plan ->
                    val price = prices[plan.stockCode]
                    val evaluation = price?.let {
                        MaDcaStrategyCalculator.evaluate(
                            closes = closes[plan.stockCode].orEmpty(),
                            currentPrice = it,
                            maPeriod = plan.maPeriod,
                            sellHalfPercent = plan.sellHalfPercent,
                            sellAllPercent = plan.sellAllPercent
                        )
                    }
                    val holding = holdingsByCode[plan.stockCode] ?: 0
                    StrategyPlanItem(
                        plan = plan,
                        currentPrice = price,
                        evaluation = evaluation,
                        holdingShares = holding,
                        sellTargetShares = evaluation?.let {
                            MaDcaStrategyCalculator.sellSharesFor(it.signal, holding)
                        } ?: 0,
                        dcaBuyShares = if (evaluation?.signal == MaDcaSignal.DCA_WINDOW && price != null) {
                            MaDcaStrategyCalculator.dcaBuyShares(plan.dcaAmount, price)
                        } else 0
                    )
                }
                _uiState.update {
                    it.copy(items = items, stocks = stocks, isLoading = false)
                }
                recomputePreview()
                // 后台刷新计划涉及股票的现价与日线收盘（吞异常，§4.3）
                refreshMarketDataFor(
                    (plans.map { it.stockCode } + _uiState.value.selectedStockCode)
                        .filter { it.isNotBlank() }.distinct()
                )
                // 个股详情页入口：首次拿到自选股且 initialStockCode 命中时，
                // 自动打开编辑器并预选该标的
                if (!initialStockHandled && initialStockCode.isNotBlank() &&
                    stocks.any { it.code == initialStockCode }
                ) {
                    initialStockHandled = true
                    showEditor()
                    onStockSelected(initialStockCode)
                }
            }
        }
        // 通知权限被系统关闭时给出可见提示（卖出阈值提醒会静默失效）
        viewModelScope.launch {
            val blocked = runCatching { !alertNotifier.canNotify() }.getOrNull()
            _uiState.update { it.copy(notificationBlocked = blocked) }
        }
    }

    /** combine 5 流的中间数据载体（避免 Pair/Triple 嵌套）。 */
    private data class StrategyUiStateHolder(
        val plans: List<StrategyPlanEntity>,
        val stocks: List<StockEntity>,
        val prices: Map<String, Double>,
        val closes: Map<String, List<Double>>,
        val transactions: List<com.stock.dividend.data.local.entity.TransactionEntity>
    )

    /** 编辑器预览：按输入参数对已选标的评估（参数非法/数据不足返回 null）。 */
    private fun previewEvaluate(
        state: StrategyPlanUiState,
        stocks: List<StockEntity>,
        prices: Map<String, Double>,
        closes: Map<String, List<Double>>
    ): MaDcaEvaluation? {
        val maPeriod = state.maPeriodInput.trim().toIntOrNull() ?: return null
        val half = state.sellHalfInput.trim().toDoubleOrNull() ?: return null
        val all = state.sellAllInput.trim().toDoubleOrNull() ?: return null
        if (stocks.none { it.code == state.selectedStockCode }) return null
        val price = prices[state.selectedStockCode] ?: return null
        return MaDcaStrategyCalculator.evaluate(
            closes = closes[state.selectedStockCode].orEmpty(),
            currentPrice = price,
            maPeriod = maPeriod,
            sellHalfPercent = half,
            sellAllPercent = all
        )
    }

    /** 编辑器实时预览重算（参数输入/标的选择/行情刷新后调用）。 */
    private fun recomputePreview() {
        val state = _uiState.value
        if (!state.showEditor || state.selectedStockCode.isBlank()) return
        val preview = previewEvaluate(state, state.stocks, pricesByCode.value, lastCloses)
        if (preview != state.previewEvaluation) {
            _uiState.update { it.copy(previewEvaluation = preview) }
        }
    }

    /** 后台刷新现价 + 日线收盘（均线所需最大周期；失败静默保留旧值）。 */
    private fun refreshMarketDataFor(codes: List<String>) {
        if (codes.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val quotes = marketDataPlane.getPricesForCodes(codes)
                if (quotes.isNotEmpty()) pricesByCode.value = quotes
            }
            val state = _uiState.value
            val periodsByCode = state.items.groupBy({ it.plan.stockCode }) { it.plan.maPeriod } +
                    // 编辑器选中的标的按当前输入周期拉（新标的尚无计划）
                    mapOf(
                        state.selectedStockCode to
                                listOfNotNull(state.maPeriodInput.trim().toIntOrNull())
                    ).filterKeys { it.isNotBlank() }
            coroutineScope {
                codes.map { code ->
                    async {
                        val bars = periodsByCode[code]?.maxOrNull() ?: DEFAULT_MA_PERIOD
                        runCatching {
                            marketDataPlane.getKlines(code, KlinePeriod.DAILY, bars).map { it.close }
                        }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { closes ->
                            closesByCode.update { it + (code to closes) }
                            lastCloses = lastCloses + (code to closes)
                        }
                    }
                }.awaitAll()
            }
            recomputePreview()
        }
    }

    // ── 编辑器 ──

    fun showEditor() {
        _uiState.update {
            it.copy(
                showEditor = true,
                selectedStockCode = "",
                maPeriodInput = "250",
                sellHalfInput = "7.5",
                sellAllInput = "15",
                dcaAmountInput = "1000",
                noteInput = "",
                notifyEnabledInput = true,
                previewEvaluation = null,
                saveError = null,
                editingId = null,
                editingCreatedAt = null
            )
        }
    }

    fun dismissEditor() {
        _uiState.update { it.copy(showEditor = false, saveError = null) }
    }

    fun onStockSelected(code: String) {
        _uiState.update { it.copy(selectedStockCode = code, saveError = null) }
        // 选中后立刻拉该标的现价+日线，驱动编辑器预览
        refreshMarketDataFor(listOf(code))
    }

    fun onMaPeriodChanged(v: String) {
        _uiState.update { it.copy(maPeriodInput = v, saveError = null) }
        recomputePreview()
    }

    fun onSellHalfChanged(v: String) {
        _uiState.update { it.copy(sellHalfInput = v, saveError = null) }
        recomputePreview()
    }

    fun onSellAllChanged(v: String) {
        _uiState.update { it.copy(sellAllInput = v, saveError = null) }
        recomputePreview()
    }

    fun onDcaAmountChanged(v: String) = _uiState.update { it.copy(dcaAmountInput = v, saveError = null) }

    fun onNoteChanged(v: String) = _uiState.update { it.copy(noteInput = v) }

    fun onNotifyEnabledChanged(v: Boolean) = _uiState.update { it.copy(notifyEnabledInput = v) }

    fun editPlan(plan: StrategyPlanEntity) {
        _uiState.update {
            it.copy(
                showEditor = true,
                selectedStockCode = plan.stockCode,
                maPeriodInput = plan.maPeriod.toString(),
                sellHalfInput = trimNum(plan.sellHalfPercent),
                sellAllInput = trimNum(plan.sellAllPercent),
                dcaAmountInput = trimNum(plan.dcaAmount),
                noteInput = plan.note.orEmpty(),
                notifyEnabledInput = plan.notifyEnabled,
                previewEvaluation = null,
                saveError = null,
                editingId = plan.id,
                editingCreatedAt = plan.createdAt
            )
        }
        refreshMarketDataFor(listOf(plan.stockCode))
    }

    /** Double → 展示字符串（去掉无意义尾零：7.5 而非 7.5000…）。 */
    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    fun savePlan() {
        val state = _uiState.value
        if (state.selectedStockCode.isBlank()) {
            _uiState.update { it.copy(saveError = "请先选择标的") }
            return
        }
        val stock = state.stocks.firstOrNull { it.code == state.selectedStockCode }
        if (stock == null) {
            _uiState.update { it.copy(saveError = "标的不在自选列表") }
            return
        }
        val maPeriod = state.maPeriodInput.trim().toIntOrNull()
        val half = state.sellHalfInput.trim().toDoubleOrNull()
        val all = state.sellAllInput.trim().toDoubleOrNull()
        val amount = state.dcaAmountInput.trim().toDoubleOrNull()
        if (maPeriod == null || half == null || all == null || amount == null) {
            _uiState.update { it.copy(saveError = "参数格式不正确") }
            return
        }
        val error = MaDcaStrategyCalculator.validateParams(maPeriod, half, all, amount)
        if (error != null) {
            _uiState.update { it.copy(saveError = error) }
            return
        }
        val now = System.currentTimeMillis()
        val entity = StrategyPlanEntity(
            id = state.editingId ?: UUID.randomUUID().toString(),
            stockCode = stock.code,
            stockName = stock.name,
            strategyType = STRATEGY_TYPE_MA_DCA,
            maPeriod = maPeriod,
            sellHalfPercent = half,
            sellAllPercent = all,
            dcaAmount = amount,
            note = state.noteInput.trim().takeIf { it.isNotEmpty() },
            notifyEnabled = state.notifyEnabledInput,
            // 编辑视为参数已变：清空卖出档提醒状态，重新按新阈值边沿触发
            lastNotifiedSellTier = null,
            createdAt = state.editingCreatedAt ?: now,
            updatedAt = now
        )
        viewModelScope.launch {
            runCatching { strategyPlanRepository.upsert(entity) }
                .onSuccess { _uiState.update { it.copy(showEditor = false, saveError = null) } }
                .onFailure { e ->
                    _uiState.update { it.copy(saveError = "保存失败：${e.message ?: "未知错误"}") }
                }
        }
    }

    // ── 列表操作 ──

    fun toggleNotify(plan: StrategyPlanEntity) {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching {
                strategyPlanRepository.upsert(plan.copy(notifyEnabled = !plan.notifyEnabled, updatedAt = now))
            }
        }
    }

    fun requestDelete(plan: StrategyPlanEntity) {
        _uiState.update { it.copy(deletingPlan = plan) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deletingPlan = null) }
    }

    fun confirmDelete() {
        val plan = _uiState.value.deletingPlan ?: return
        viewModelScope.launch {
            runCatching { strategyPlanRepository.delete(plan.id) }
            _uiState.update { it.copy(deletingPlan = null) }
        }
    }

    /** 下拉刷新：重拉计划标的现价与日线。 */
    fun refresh() {
        val codes = _uiState.value.items.map { it.plan.stockCode }.distinct()
        refreshMarketDataFor(codes)
    }

    companion object {
        /** 未有任何计划周期信息时按默认年线周期拉日线。 */
        const val DEFAULT_MA_PERIOD = 250
    }
}
