package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.GridAnchor
import com.stock.dividend.data.repository.GridAnchorCalculator
import com.stock.dividend.data.repository.GridCalculator
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.GridResult
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.StockRepository
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
 * 已保存的网格计划 + 其对应的当前价（用于「下一档」提示）。
 */
@Stable
data class GridPlanItem(
    val plan: GridPlanEntity,
    val currentPrice: Double?,
    val result: GridResult
)

@Stable
data class GridPlanUiState(
    /** 已保存的计划列表（按更新时间倒序），各含当前价提示。 */
    val items: List<GridPlanItem> = emptyList(),
    /** 用户自选股（生成器标的下拉用）。 */
    val stocks: List<StockEntity> = emptyList(),
    val isLoading: Boolean = true,
    // ── 生成器参数 ──
    val showGenerator: Boolean = false,
    val selectedStockCode: String = "",
    val basePriceInput: String = "",
    val lowPriceInput: String = "",
    val highPriceInput: String = "",
    val gridsInput: String = "4",
    val totalCapitalInput: String = "100000",
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
    val editingId: String? = null
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
    private val dividendRepository: DividendRepository
) : ViewModel() {

    /** 从个股详情页跳转时携带的 stockCode（gridPlanFor/{code} 路由参数）；全局入口为空。 */
    private val initialStockCode: String = savedStateHandle["code"] ?: ""

    private val _uiState = MutableStateFlow(GridPlanUiState())
    val uiState: StateFlow<GridPlanUiState> = _uiState.asStateFlow()

    /** code → 当前价（用于已保存计划的「下一档」提示）。 */
    private val pricesByCode = MutableStateFlow<Map<String, Double>>(emptyMap())

    init {
        // 列表 + 自选股：combine 计划、股票、当前价 → 带「下一档」提示的列表项
        viewModelScope.launch {
            combine(
                gridPlanRepository.observeAll(),
                stockRepository.observeAllStocks(),
                pricesByCode
            ) { plans, stocks, prices ->
                val codeToName = stocks.associate { it.code to it.name }
                Triple(plans, stocks, codeToName to prices)
            }.collect { (plans, stocks, namesAndPrices) ->
                val (codeToName, prices) = namesAndPrices
                val items = plans.map { plan ->
                    val price = prices[plan.stockCode]
                    GridPlanItem(
                        plan = plan,
                        currentPrice = price,
                        result = GridCalculator.generate(
                            basePrice = plan.basePrice,
                            lowPrice = plan.lowPrice,
                            highPrice = plan.highPrice,
                            grids = plan.grids,
                            totalCapital = plan.totalCapital,
                            currentPrice = price
                        )
                    )
                }
                _uiState.value = _uiState.value.copy(
                    items = items,
                    stocks = stocks,
                    isLoading = false
                )
                // 后台刷新计划涉及股票的当前价（吞异常，§4.3）
                refreshPricesFor(plans.map { it.stockCode }.distinct())

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
    }

    /** 防止 initialStockCode 的自动锚定在每次自选股发射时重复触发。 */
    private var initialStockHandled = false

    private fun refreshPricesFor(codes: List<String>) {
        if (codes.isEmpty()) return
        viewModelScope.launch {
            try {
                val stocks = stockRepository.observeAllStocksForSnapshot()
                    .filter { it.code in codes }
                if (stocks.isEmpty()) return@launch
                val quotes = stockRepository.fetchQuotes(stocks)
                pricesByCode.value = quotes.filterValues { it > 0.0 }
            } catch (_: Exception) {
                // 网络失败静默，保留空价（提示项为 null）
            }
        }
    }

    // ── 生成器 ──────────────────────────────────────────

    fun showGenerator() {
        _uiState.value = _uiState.value.copy(
            showGenerator = true,
            editingId = null,
            selectedStockCode = "",
            basePriceInput = "",
            lowPriceInput = "",
            highPriceInput = "",
            gridsInput = "4",
            totalCapitalInput = "100000",
            targetYieldInput = "6",
            anchorInfo = null,
            isAnchoring = false,
            anchorError = null,
            saveError = null
        )
        recalculatePreview()
    }

    fun dismissGenerator() {
        _uiState.value = _uiState.value.copy(showGenerator = false, editingId = null, preview = null)
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
            preview = GridCalculator.generate(base, low, high, grids, capital, price)
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
            createdAt = if (s.editingId != null) now else now,
            updatedAt = now
        )
        viewModelScope.launch {
            gridPlanRepository.upsert(plan)
            _uiState.value = _uiState.value.copy(showGenerator = false, editingId = null, preview = null)
        }
    }

    private fun setSaveError(message: String) {
        _uiState.value = _uiState.value.copy(saveError = message)
    }

    fun editPlan(plan: GridPlanEntity) {
        _uiState.value = _uiState.value.copy(
            showGenerator = true,
            editingId = plan.id,
            selectedStockCode = plan.stockCode,
            basePriceInput = String.format(java.util.Locale.US, "%.2f", plan.basePrice),
            lowPriceInput = String.format(java.util.Locale.US, "%.2f", plan.lowPrice),
            highPriceInput = String.format(java.util.Locale.US, "%.2f", plan.highPrice),
            gridsInput = plan.grids.toString(),
            totalCapitalInput = String.format(java.util.Locale.US, "%.0f", plan.totalCapital)
        )
        recalculatePreview()
    }

    fun deletePlan(id: String) {
        viewModelScope.launch { gridPlanRepository.delete(id) }
    }
}
