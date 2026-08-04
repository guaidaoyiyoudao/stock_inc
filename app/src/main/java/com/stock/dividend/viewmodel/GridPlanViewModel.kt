package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.GridPlanEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.GridCalculator
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.GridResult
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** 生成器预览结果（随参数实时重算）。 */
    val preview: GridResult? = null,
    val editingId: String? = null
)

/**
 * 网格交易计划 ViewModel：列表展示 + 生成器（参数实时预览）+ 保存/删除。
 * 网格仅做计划与提示，不联网下单。
 */
@HiltViewModel
class GridPlanViewModel @Inject constructor(
    private val gridPlanRepository: GridPlanRepository,
    private val stockRepository: StockRepository
) : ViewModel() {

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
            }
        }
    }

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
            totalCapitalInput = "100000"
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

    private fun update(transform: GridPlanUiState.() -> GridPlanUiState) {
        _uiState.value = _uiState.value.transform()
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
        val base = s.basePriceInput.toDoubleOrNull() ?: return
        val low = s.lowPriceInput.toDoubleOrNull() ?: return
        val high = s.highPriceInput.toDoubleOrNull() ?: return
        val grids = s.gridsInput.toIntOrNull() ?: return
        val capital = s.totalCapitalInput.toDoubleOrNull() ?: return
        if (s.selectedStockCode.isBlank()) return

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
