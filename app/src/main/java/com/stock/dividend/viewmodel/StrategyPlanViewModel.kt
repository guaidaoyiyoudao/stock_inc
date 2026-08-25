package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DIVIDEND_REINVEST
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_DUAL_MA
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DCA
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_MA_DEVIATION
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_TAKE_PROFIT
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_VALUATION_BAND
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_VALUE_AVERAGING
import com.stock.dividend.data.local.entity.STRATEGY_TYPE_YIELD_BAND
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.StrategyPlanEntity
import com.stock.dividend.data.notification.DividendAlertNotifier
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.MaDcaStrategyCalculator
import com.stock.dividend.data.repository.StrategyEvaluator
import com.stock.dividend.data.repository.StrategyEvaluation
import com.stock.dividend.data.repository.StrategyInputAssembler
import com.stock.dividend.data.repository.StrategyParams
import com.stock.dividend.data.repository.StrategyPlanRepository
import com.stock.dividend.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 已保存的策略计划 + 当前统一评估。[evaluation] 为 null 表示数据不足
 * （上市不足均线周期/无现价/估值或分红数据缺失）。
 */
@Stable
data class StrategyPlanItem(
    val plan: StrategyPlanEntity,
    val currentPrice: Double?,
    val evaluation: StrategyEvaluation?
)

/** 编辑器通用参数字段描述（非 MA_DCA 类型按此渲染表单）。 */
@Stable
data class StrategyEditorField(
    val key: String,
    val label: String,
    /** 数值输入（Int 语义也走数字键盘）。 */
    val numeric: Boolean = true,
    /** 估值带的 PE/PB 切换（非文本输入）。 */
    val metricToggle: Boolean = false
)

/** 编辑器是否需要「单次买入金额」字段（走 dcaAmount 专用列）。 */
private fun usesDcaAmount(type: String) =
    type == STRATEGY_TYPE_MA_DCA || type == STRATEGY_TYPE_YIELD_BAND || type == STRATEGY_TYPE_MA_DEVIATION

@Stable
data class StrategyPlanUiState(
    val items: List<StrategyPlanItem> = emptyList(),
    /** 用户自选股（编辑器标的搜索用）。 */
    val stocks: List<StockEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** 系统通知不可用（权限被关）→ 卖出信号提醒无法推送；null=未知/未检查。 */
    val notificationBlocked: Boolean? = null,
    // ── 编辑器（BottomSheet）──
    val showEditor: Boolean = false,
    /** 当前编辑的策略类型（决定参数表单）。 */
    val strategyTypeInput: String = STRATEGY_TYPE_MA_DCA,
    val selectedStockCode: String = "",
    // MA_DCA 专用列输入
    val maPeriodInput: String = "250",
    val sellHalfInput: String = "7.5",
    val sellAllInput: String = "15",
    val dcaAmountInput: String = "1000",
    /** 非 MA_DCA 类型的 params 输入（字段 key → 字符串）。 */
    val paramInputs: Map<String, String> = emptyMap(),
    val noteInput: String = "",
    val notifyEnabledInput: Boolean = true,
    /** 编辑器实时预览（已选标的且数据就绪时非空）。 */
    val previewEvaluation: StrategyEvaluation? = null,
    val saveError: String? = null,
    val editingId: String? = null,
    /** 编辑中的计划原 createdAt（保存时保留）。 */
    val editingCreatedAt: Long? = null,
    /** 删除确认弹窗中的计划；null=不显示。 */
    val deletingPlan: StrategyPlanEntity? = null
)

/**
 * 交易策略 ViewModel：列表（全部类型统一评估）+ 编辑器（类型选择 + 通用参数表单 +
 * 实时预览）+ 保存/删除。策略仅做信号提示与记账辅助，不联网下单。
 */
@HiltViewModel
class StrategyPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val strategyPlanRepository: StrategyPlanRepository,
    private val marketDataPlane: MarketDataPlane,
    private val transactionRepository: TransactionRepository,
    private val strategyInputAssembler: StrategyInputAssembler,
    private val alertNotifier: DividendAlertNotifier
) : ViewModel() {

    /** 从个股详情/今日页跳转时携带的 stockCode（strategyPlanFor/{code}）；全局入口为空。 */
    private val initialStockCode: String = savedStateHandle["code"] ?: ""

    private val _uiState = MutableStateFlow(StrategyPlanUiState())
    val uiState = _uiState.asStateFlow()

    /** code → 当前价（列表提示 + 预览用）。 */
    private val pricesByCode = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** 防止 initialStockCode 的自动打开编辑器在每次自选股发射时重复触发。 */
    private var initialStockHandled = false

    init {
        // 列表 + 自选股 + 现价 + 交易流水（记账后重算）：collect 内经装配器+调度器统一评估
        viewModelScope.launch {
            combine(
                strategyPlanRepository.observeAll(),
                marketDataPlane.observeAllStocks(),
                pricesByCode,
                transactionRepository.observeAll()
            ) { plans, stocks, prices, _ ->
                Triple(plans, stocks, prices)
            }.collect { (plans, stocks, prices) ->
                val items = if (plans.isEmpty()) emptyList() else runCatching {
                    val inputs = strategyInputAssembler.assemble(plans, prices)
                    plans.map { plan ->
                        StrategyPlanItem(
                            plan = plan,
                            currentPrice = prices[plan.stockCode],
                            evaluation = inputs[plan.id]?.let { StrategyEvaluator.evaluate(plan, it) }
                        )
                    }
                }.getOrDefault(plans.map { StrategyPlanItem(it, prices[it.stockCode], null) })
                _uiState.update {
                    it.copy(items = items, stocks = stocks, isLoading = false)
                }
                // 后台刷新计划涉及股票的现价（吞异常，§4.3）
                if (plans.isNotEmpty()) {
                    runCatching {
                        val quotes = marketDataPlane.getPricesForCodes(plans.map { it.stockCode }.distinct())
                        if (quotes.isNotEmpty()) pricesByCode.value = quotes
                    }
                }
                // 个股详情页入口：首次拿到自选股且 initialStockCode 命中时自动打开编辑器并预选
                if (!initialStockHandled && initialStockCode.isNotBlank() &&
                    stocks.any { it.code == initialStockCode }
                ) {
                    initialStockHandled = true
                    showEditor()
                    onStockSelected(initialStockCode)
                }
            }
        }
        // 通知权限被系统关闭时给出可见提示（卖出信号提醒会静默失效）
        viewModelScope.launch {
            val blocked = runCatching { !alertNotifier.canNotify() }.getOrNull()
            _uiState.update { it.copy(notificationBlocked = blocked) }
        }
    }

    // ── 编辑器 ──

    fun showEditor() {
        _uiState.update {
            it.copy(
                showEditor = true,
                strategyTypeInput = STRATEGY_TYPE_MA_DCA,
                selectedStockCode = "",
                maPeriodInput = "250",
                sellHalfInput = "7.5",
                sellAllInput = "15",
                dcaAmountInput = "1000",
                paramInputs = emptyMap(),
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

    /** 切换策略类型：重置参数输入为该类型默认值（标的/备注保留）。 */
    fun onStrategyTypeChanged(type: String) {
        _uiState.update {
            it.copy(
                strategyTypeInput = type,
                paramInputs = StrategyParams.defaultsFor(type),
                previewEvaluation = null,
                saveError = null
            )
        }
        refreshPreview()
    }

    fun onStockSelected(code: String) {
        _uiState.update { it.copy(selectedStockCode = code, saveError = null) }
        // 选中后立刻拉该标的现价，驱动列表与预览（merge 而非整体替换——
        // 其他计划标的的价格不再闪断显示"—"，2026-08-24 评审修复）
        viewModelScope.launch {
            runCatching {
                marketDataPlane.getPricesForCodes(listOf(code)).takeIf { it.isNotEmpty() }
                    ?.let { pricesByCode.value = pricesByCode.value + it }
            }
            refreshPreview()
        }
    }

    fun onMaPeriodChanged(v: String) {
        _uiState.update { it.copy(maPeriodInput = v, saveError = null) }
        refreshPreview()
    }

    fun onSellHalfChanged(v: String) {
        _uiState.update { it.copy(sellHalfInput = v, saveError = null) }
        refreshPreview()
    }

    fun onSellAllChanged(v: String) {
        _uiState.update { it.copy(sellAllInput = v, saveError = null) }
        refreshPreview()
    }

    fun onDcaAmountChanged(v: String) {
        _uiState.update { it.copy(dcaAmountInput = v, saveError = null) }
    }

    fun onParamChanged(key: String, value: String) {
        _uiState.update {
            it.copy(paramInputs = it.paramInputs + (key to value), saveError = null)
        }
        refreshPreview()
    }

    fun onNoteChanged(v: String) = _uiState.update { it.copy(noteInput = v) }

    fun onNotifyEnabledChanged(v: Boolean) = _uiState.update { it.copy(notifyEnabledInput = v) }

    fun editPlan(plan: StrategyPlanEntity) {
        _uiState.update {
            it.copy(
                showEditor = true,
                strategyTypeInput = plan.strategyType,
                selectedStockCode = plan.stockCode,
                maPeriodInput = plan.maPeriod.toString(),
                sellHalfInput = trimNum(plan.sellHalfPercent),
                sellAllInput = trimNum(plan.sellAllPercent),
                dcaAmountInput = trimNum(plan.dcaAmount),
                paramInputs = StrategyParams.toInputs(plan.strategyType, plan.params),
                noteInput = plan.note.orEmpty(),
                notifyEnabledInput = plan.notifyEnabled,
                previewEvaluation = null,
                saveError = null,
                editingId = plan.id,
                editingCreatedAt = plan.createdAt
            )
        }
        viewModelScope.launch {
            runCatching {
                marketDataPlane.getPricesForCodes(listOf(plan.stockCode)).takeIf { it.isNotEmpty() }
                    ?.let { pricesByCode.value = pricesByCode.value + it }
            }
            refreshPreview()
        }
    }

    /** Double → 展示字符串（去掉无意义尾零：7.5 而非 7.5000…）。 */
    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** 按编辑器当前状态构造临时计划（预览用，不落库）。 */
    private fun buildTransientPlan(state: StrategyPlanUiState, stock: StockEntity): StrategyPlanEntity? {
        val dcaAmount = state.dcaAmountInput.trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1000.0
        return if (state.strategyTypeInput == STRATEGY_TYPE_MA_DCA) {
            val maPeriod = state.maPeriodInput.trim().toIntOrNull() ?: return null
            val half = state.sellHalfInput.trim().toDoubleOrNull() ?: return null
            val all = state.sellAllInput.trim().toDoubleOrNull() ?: return null
            StrategyPlanEntity(
                id = PREVIEW_PLAN_ID, stockCode = stock.code, stockName = stock.name,
                strategyType = STRATEGY_TYPE_MA_DCA,
                maPeriod = maPeriod, sellHalfPercent = half, sellAllPercent = all,
                dcaAmount = dcaAmount
            )
        } else {
            val (encoded, error) = StrategyParams.fromInputs(state.strategyTypeInput, state.paramInputs)
            if (encoded == null || error != null) return null
            StrategyPlanEntity(
                id = PREVIEW_PLAN_ID, stockCode = stock.code, stockName = stock.name,
                strategyType = state.strategyTypeInput, dcaAmount = dcaAmount, params = encoded
            )
        }
    }

    /** 编辑器实时预览重算（参数输入/标的选择/行情刷新后调用）。 */
    private fun refreshPreview() {
        val state = _uiState.value
        if (!state.showEditor || state.selectedStockCode.isBlank()) return
        val stock = state.stocks.firstOrNull { it.code == state.selectedStockCode } ?: return
        val transient = buildTransientPlan(state, stock) ?: run {
            _uiState.update { it.copy(previewEvaluation = null) }
            return
        }
        viewModelScope.launch {
            val preview = runCatching {
                val inputs = strategyInputAssembler.assemble(listOf(transient), pricesByCode.value)
                inputs[PREVIEW_PLAN_ID]?.let { StrategyEvaluator.evaluate(transient, it) }
            }.getOrNull()
            // 状态可能已被用户改动，仅当仍停留在同一编辑会话时写入
            _uiState.update {
                if (it.showEditor && it.selectedStockCode == state.selectedStockCode &&
                    it.strategyTypeInput == state.strategyTypeInput
                ) it.copy(previewEvaluation = preview) else it
            }
        }
    }

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
        val now = System.currentTimeMillis()
        val entity: StrategyPlanEntity = if (state.strategyTypeInput == STRATEGY_TYPE_MA_DCA) {
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
            StrategyPlanEntity(
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
                lastNotifiedSellTier = null,   // 编辑视为参数已变：清空档位状态重新边沿触发
                params = null,
                createdAt = state.editingCreatedAt ?: now,
                updatedAt = now
            )
        } else {
            val dcaAmount = state.dcaAmountInput.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
            if (dcaAmount == null && usesDcaAmount(state.strategyTypeInput)) {
                _uiState.update { it.copy(saveError = "单次买入金额必须大于 0") }
                return
            }
            val (encoded, error) = StrategyParams.fromInputs(state.strategyTypeInput, state.paramInputs)
            if (encoded == null && error == null) {
                _uiState.update { it.copy(saveError = "未知策略类型") }
                return
            }
            if (error != null) {
                _uiState.update { it.copy(saveError = error) }
                return
            }
            StrategyPlanEntity(
                id = state.editingId ?: UUID.randomUUID().toString(),
                stockCode = stock.code,
                stockName = stock.name,
                strategyType = state.strategyTypeInput,
                dcaAmount = dcaAmount ?: 1000.0,
                note = state.noteInput.trim().takeIf { it.isNotEmpty() },
                notifyEnabled = state.notifyEnabledInput,
                lastNotifiedSellTier = null,
                params = encoded,
                createdAt = state.editingCreatedAt ?: now,
                updatedAt = now
            )
        }
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

    /** 下拉刷新：重拉计划标的现价（列表随 pricesByCode 重算）。 */
    fun refresh() {
        val codes = _uiState.value.items.map { it.plan.stockCode }.distinct()
        if (codes.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val quotes = marketDataPlane.getPricesForCodes(codes)
                if (quotes.isNotEmpty()) pricesByCode.value = quotes
            }
        }
    }

    companion object {
        /** 编辑器预览临时计划 id（不落库）。 */
        const val PREVIEW_PLAN_ID = "preview"

        /** 编辑器可选策略类型（固定顺序：首版年线定投在前）。 */
        val STRATEGY_TYPES: List<Pair<String, String>> = listOf(
            STRATEGY_TYPE_MA_DCA to "年线定投",
            STRATEGY_TYPE_TAKE_PROFIT to "目标止盈",
            STRATEGY_TYPE_YIELD_BAND to "股息率带",
            STRATEGY_TYPE_DUAL_MA to "双均线趋势",
            STRATEGY_TYPE_MA_DEVIATION to "均线偏离回归",
            STRATEGY_TYPE_VALUE_AVERAGING to "价值平均法",
            STRATEGY_TYPE_VALUATION_BAND to "估值带 PE/PB",
            STRATEGY_TYPE_DIVIDEND_REINVEST to "分红再投"
        )

        /** 各类型的编辑器参数字段（顺序即渲染顺序）。 */
        fun editorFields(type: String): List<StrategyEditorField> = when (type) {
            STRATEGY_TYPE_TAKE_PROFIT -> listOf(
                StrategyEditorField("halfGainPercent", "卖出一半涨幅（%）"),
                StrategyEditorField("allGainPercent", "清仓涨幅（%）")
            )
            STRATEGY_TYPE_YIELD_BAND -> listOf(
                StrategyEditorField("buyYieldPercent", "买入股息率（%）"),
                StrategyEditorField("addYieldPercent", "加仓股息率（%）"),
                StrategyEditorField("sellYieldPercent", "卖出股息率（%）")
            )
            STRATEGY_TYPE_DUAL_MA -> listOf(
                StrategyEditorField("fastPeriod", "快线周期（日）"),
                StrategyEditorField("slowPeriod", "慢线周期（日）")
            )
            STRATEGY_TYPE_MA_DEVIATION -> listOf(
                StrategyEditorField("maPeriod", "均线周期（日）"),
                StrategyEditorField("stepPercent", "偏离步长（%/档）"),
                StrategyEditorField("buyLevels", "买入档数（1~10）")
            )
            STRATEGY_TYPE_VALUE_AVERAGING -> listOf(
                StrategyEditorField("perPeriodAmount", "每期增长金额（元）")
            )
            STRATEGY_TYPE_VALUATION_BAND -> listOf(
                StrategyEditorField("metric", "估值指标", numeric = false, metricToggle = true),
                StrategyEditorField("lowThreshold", "低估值线"),
                StrategyEditorField("highThreshold", "高估值线")
            )
            STRATEGY_TYPE_DIVIDEND_REINVEST -> listOf(
                StrategyEditorField("lookaheadDays", "展望天数（1~90）")
            )
            else -> emptyList()
        }

        /** 该类型编辑器是否需要「单次买入金额」字段（dcaAmount 专用列）。 */
        fun editorUsesDcaAmount(type: String): Boolean = usesDcaAmount(type)
    }
}
