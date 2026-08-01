package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.remote.LlmApi
import com.stock.dividend.data.remote.dto.LlmChatRequest
import com.stock.dividend.data.remote.dto.LlmMessage
import com.stock.dividend.data.repository.BollBand
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.BuyThresholdStatus
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.ForecastCalculator
import com.stock.dividend.data.repository.Fundamentals
import com.stock.dividend.data.repository.KlinePeriod
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.enrichPayoutRatio
import com.stock.dividend.data.repository.toUserStrategyRef
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.data.repository.LlmConfigSource
import com.stock.dividend.data.repository.StockLlmAnalysisParser
import com.stock.dividend.data.repository.StockLlmAnalysisState
import com.stock.dividend.data.repository.StockLlmInput
import com.stock.dividend.data.repository.StockLlmPromptBuilder
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.computeBuyThreshold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

@Stable
data class ForecastDetail(
    val avgCashPerShare: Double,
    val forecastIncome: Double,
    val actualYears: Int
)

@Stable
data class DividendRatePoint(
    val period: String,
    val label: String,
    val ratePercent: Double
)

@Stable
data class StockDetailUiState(
    val stock: StockEntity? = null,
    val dividends: List<DividendEntity> = emptyList(),
    val dividendRatePoints: List<DividendRatePoint> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val forecast: ForecastDetail? = null,
    val allForecasts: Map<String, ForecastDetail> = emptyMap(),
    val selectedPeriod: String = "3",
    val visibleCount: Int = 5,
    val buyThreshold: BuyThresholdStatus? = null,
    val fundamentals: Fundamentals? = null,
    val fundamentalsLoading: Boolean = true,
    val llmAnalysis: StockLlmAnalysisState = StockLlmAnalysisState.Idle
)

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val bondYieldRepository: BondYieldRepository,
    private val llmApi: LlmApi,
    private val llmConfigSource: LlmConfigSource,
    private val tradeStrategyRepository: TradeStrategyRepository
) : ViewModel() {

    private val stockCode: String = savedStateHandle["code"] ?: ""

    private val _uiState = MutableStateFlow(StockDetailUiState())
    val uiState: StateFlow<StockDetailUiState> = _uiState.asStateFlow()

    /** fetchFundamentals 的原始结果（payoutRatio 未补全）；由 recomputeFundamentals 补全后写入 uiState。 */
    private var rawFundamentals: Fundamentals? = null

    private val stockFlow = stockRepository.observeStock(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val dividendsFlow = dividendRepository.observeDividends(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(stockFlow, dividendsFlow) { stock, dividends ->
                Pair(stock, dividends)
            }.collect { (stock, dividends) ->
                val dividendRatePoints = deriveDividendRatePoints(dividends)
                if (stock != null) {
                    val allForecasts = mutableMapOf<String, ForecastDetail>()
                    for (period in listOf("1", "3", "5")) {
                        val years = period.toInt()
                        val result = ForecastCalculator.calculateForecastIncome(
                            dividends, stock.shares, years
                        )
                        if (result != null) {
                            allForecasts[period] = ForecastDetail(
                                avgCashPerShare = result.avgCashPerShare,
                                forecastIncome = stock.shares * result.avgCashPerShare,
                                actualYears = result.actualYears
                            )
                        }
                    }
                    val selectedPeriod = _uiState.value.selectedPeriod.let { period ->
                        if (allForecasts.containsKey(period)) period else allForecasts.keys.firstOrNull() ?: "3"
                    }

                    _uiState.value = _uiState.value.copy(
                        stock = stock,
                        dividends = dividends,
                        dividendRatePoints = dividendRatePoints,
                        isLoading = false,
                        visibleCount = 5,
                        allForecasts = allForecasts,
                        forecast = allForecasts[selectedPeriod],
                        selectedPeriod = selectedPeriod
                    )
                    // 标的或分红变化后，重新计算买入阈值（现价/国债异步拉取）
                    refreshBuyThreshold()
                    // 分红数据更新后，用最新 EPS_DIV 重新补全基本面派息率（见 §6.3）
                    recomputeFundamentals()
                } else {
                    _uiState.value = _uiState.value.copy(
                        dividends = dividends,
                        dividendRatePoints = dividendRatePoints,
                        isLoading = false,
                        visibleCount = 5
                    )
                }
            }
        }

        // 独立加载基本面（AGENTS §4.2：与分红 collector 解耦，各管各的字段）
        loadFundamentals()
    }

    /** 拉取单股基本面并补全派息率；失败降级为 null（红线 #2）。成功/失败均复位 fundamentalsLoading（红线 #3）。 */
    private fun loadFundamentals() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(fundamentalsLoading = true)
            val result = runCatching { stockRepository.fetchFundamentals(stockCode) }.getOrNull()
            rawFundamentals = result
            recomputeFundamentals()
            _uiState.value = _uiState.value.copy(fundamentalsLoading = false)
        }
    }

    /** 用当前分红数据（EPS_DIV）补全 [rawFundamentals] 的派息率并写入 uiState（纯函数 enrichPayoutRatio）。 */
    private fun recomputeFundamentals() {
        val raw = rawFundamentals ?: run {
            _uiState.value = _uiState.value.copy(fundamentals = null)
            return
        }
        val epsDivByDate = _uiState.value.dividends
            .filter { it.reportDate.isNotBlank() && it.cashPerShare > 0.0 }
            .associate { it.reportDate to it.cashPerShare }
        val enriched = enrichPayoutRatio(raw, epsDivByDate)
        _uiState.value = _uiState.value.copy(fundamentals = enriched)
    }

    /** 手动刷新基本面（卡片「更新」入口调用）。 */
    fun refreshFundamentals() {
        loadFundamentals()
    }

    /**
     * 异步拉取现价 + 10Y 国债收益率，结合当前分红数据计算 [BuyThresholdStatus]。
     * 任何一环失败时降级（国债用默认值，现价缺失则 reached=null）。
     */
    private fun refreshBuyThreshold() {
        val stock = _uiState.value.stock ?: return
        val dividends = _uiState.value.dividends
        viewModelScope.launch {
            val bondYield = runCatching { bondYieldRepository.fetch10YBondYield() }
                .getOrDefault(BondYieldRepository.DEFAULT_YIELD)
            val currentPrice = runCatching {
                stockRepository.fetchQuotes(listOf(stock))[stock.code]
            }.getOrNull()
            val latestCash = ForecastCalculator.latestYearlyCashPerShare(dividends)
            val status = computeBuyThreshold(
                bondYield10Y = bondYield,
                multiplier = stock.buyThresholdMultiplier,
                latestYearlyCashPerShare = latestCash,
                currentPrice = currentPrice
            )
            _uiState.value = _uiState.value.copy(buyThreshold = status)
        }
    }

    /**
     * 更新标的买入阈值倍数并重新计算阈值判定。
     */
    fun updateBuyThresholdMultiplier(multiplier: Double) {
        val code = stockCode
        viewModelScope.launch {
            stockRepository.updateBuyThresholdMultiplier(code, multiplier)
        }
        // 乐观更新：先用新倍数重算一次，stockFlow 回来后会再校准
        val state = _uiState.value
        val status = state.buyThreshold
        if (status != null) {
            _uiState.value = state.copy(
                buyThreshold = status.copy(
                    multiplier = multiplier,
                    targetYieldPercent = status.bondYield10Y * multiplier,
                    reached = status.currentYieldPercent?.let { it >= status.bondYield10Y * multiplier }
                )
            )
        }
    }

    fun refreshDividends() {
        val stock = _uiState.value.stock ?: return
        if (_uiState.value.isRefreshing) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val securityCode = stockCode.substringAfter(".")
            dividendRepository.fetchAndCacheDividends(stockCode, securityCode)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun loadMoreDividends() {
        val state = _uiState.value
        val newCount = (state.visibleCount + 5).coerceAtMost(state.dividends.size)
        _uiState.value = state.copy(visibleCount = newCount)
    }

    fun updateYieldPeriod(period: String) {
        viewModelScope.launch {
            stockRepository.updateYieldPeriod(stockCode, period)
        }
        val state = _uiState.value
        _uiState.value = state.copy(
            selectedPeriod = period,
            forecast = state.allForecasts[period]
        )
    }

    private fun deriveDividendRatePoints(dividends: List<DividendEntity>): List<DividendRatePoint> {
        return dividends
            .mapNotNull { dividend ->
                val yield = dividend.dividendYield
                if (yield == null || !yield.isFinite() || yield < 0.0) {
                    null
                } else {
                    dividend.reportDate.substringBefore("-") to yield
                }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .map { (year, yields) ->
                DividendRatePoint(
                    period = year,
                    label = year,
                    ratePercent = yields.sum()
                )
            }
            .sortedBy { it.period }
    }

    /**
     * 触发个股 AI 解读：并发拉日/周/月 BOLL + 现价，组装 [StockLlmInput]，调用已配置的 LLM。
     * 失败的周期/现价降级为 null（"—"），不阻塞分析。配置缺失返回 NotConfigured。
     */
    fun analyzeWithLlm() {
        val state = _uiState.value
        val stock = state.stock ?: return
        if (state.dividends.isEmpty()) return
        if (_uiState.value.llmAnalysis is StockLlmAnalysisState.Loading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(llmAnalysis = StockLlmAnalysisState.Loading)

            val config = llmConfigSource.observeConfig().first()
            if (!config.isComplete) {
                _uiState.value = _uiState.value.copy(llmAnalysis = StockLlmAnalysisState.NotConfigured)
                return@launch
            }

            // 并发拉三周期 BOLL（单股仅 3 请求，无需 Semaphore 限流）；失败降级 null
            val (dailyBand, weeklyBand, monthlyBand) = listOf(
                async { runCatching { stockRepository.fetchBoll(stockCode, KlinePeriod.DAILY) }.getOrNull() },
                async { runCatching { stockRepository.fetchBoll(stockCode, KlinePeriod.WEEKLY) }.getOrNull() },
                async { runCatching { stockRepository.fetchBoll(stockCode, KlinePeriod.MONTHLY) }.getOrNull() }
            ).awaitAll().let { Triple(it[0] as BollBand?, it[1] as BollBand?, it[2] as BollBand?) }

            // 现价：现拉一次（buyThreshold 的字段无法可靠反推现价）；失败降级 null
            val currentPrice = runCatching {
                stockRepository.fetchQuotes(listOf(stock))[stock.code]
            }.getOrNull()

            val input = buildStockLlmInput(stock, state, currentPrice, dailyBand, weeklyBand, monthlyBand)
            // 回流全局用户投资原则（失败降级空，不阻塞分析，红线 #2）
            val userStrategies = runCatching {
                tradeStrategyRepository.activeStrategies().map { toUserStrategyRef(it) }
            }.getOrDefault(emptyList())
            val prompt = StockLlmPromptBuilder.build(input, userStrategies)
            val url = config.baseUrl.trimEnd('/') + "/chat/completions"
            val request = LlmChatRequest(
                model = config.model,
                messages = listOf(
                    LlmMessage("system", prompt.system),
                    LlmMessage("user", prompt.user)
                )
            )

            val result = try {
                val content = llmApi.chatCompletions(url, "Bearer ${config.apiKey}", request).content
                if (content.isNullOrBlank()) {
                    StockLlmAnalysisState.Error("LLM 返回为空")
                } else {
                    StockLlmAnalysisState.Success(StockLlmAnalysisParser.parse(content))
                }
            } catch (e: HttpException) {
                StockLlmAnalysisState.Error(mapLlmHttpError(e.code()))
            } catch (_: Exception) {
                StockLlmAnalysisState.Error("网络错误，请重试")
            }
            _uiState.value = _uiState.value.copy(llmAnalysis = result)
        }
    }

    /** 清空个股 AI 解读状态，回到 Idle。 */
    fun clearLlmAnalysis() {
        if (_uiState.value.llmAnalysis is StockLlmAnalysisState.Idle) return
        _uiState.value = _uiState.value.copy(llmAnalysis = StockLlmAnalysisState.Idle)
    }

    /** 组装个股 LLM 输入快照（从当前 uiState + 拉到的 BOLL/现价构建）。 */
    private fun buildStockLlmInput(
        stock: StockEntity,
        state: StockDetailUiState,
        currentPrice: Double?,
        dailyBand: BollBand?,
        weeklyBand: BollBand?,
        monthlyBand: BollBand?
    ): StockLlmInput {
        val ratePoints = state.dividendRatePoints
            .takeIf { it.isNotEmpty() }
            ?.map { it.ratePercent }
        // 股息率取「当年累计」：同一年多笔分红累加（与 deriveDividendRatePoints / ForecastCalculator 一致）。
        // dividendRatePoints 升序，最后一项即最近一年的累计股息率；避免取单笔分红而低估。
        val latestYield = state.dividendRatePoints
            .takeIf { it.isNotEmpty() }
            ?.lastOrNull()
            ?.ratePercent
            ?.takeIf { it.isFinite() && it >= 0.0 }
        val forecast = state.allForecasts.let { all ->
            val y1 = all["1"] ?: all[all.keys.firstOrNull()]
            if (y1 != null) {
                StockLlmInput.StockLlmForecast(
                    avgCashPerShare1Y = all["1"]?.avgCashPerShare ?: y1.avgCashPerShare,
                    avgCashPerShare3Y = all["3"]?.avgCashPerShare ?: y1.avgCashPerShare,
                    avgCashPerShare5Y = all["5"]?.avgCashPerShare ?: y1.avgCashPerShare,
                    actualYears = y1.actualYears
                )
            } else null
        }
        val bt = state.buyThreshold?.let {
            StockLlmInput.StockLlmBuyThreshold(
                targetYieldPercent = it.targetYieldPercent,
                currentYieldPercent = it.currentYieldPercent,
                reached = it.reached
            )
        }
        return StockLlmInput(
            code = stock.code,
            name = stock.name,
            industry = stock.industry,
            currentPrice = currentPrice,
            dividendRatePoints = ratePoints,
            latestDividendYield = latestYield,
            forecast = forecast,
            buyThreshold = bt,
            bollDaily = dailyBand?.let { StockLlmInput.StockLlmBollPosition(ratioVsLowerPercent(currentPrice, it)) },
            bollWeekly = weeklyBand?.let { StockLlmInput.StockLlmBollPosition(ratioVsLowerPercent(currentPrice, it)) },
            bollMonthly = monthlyBand?.let { StockLlmInput.StockLlmBollPosition(ratioVsLowerPercent(currentPrice, it)) },
            fundamentals = state.fundamentals
        )
    }

    /** (price - lower) / (upper - lower) → 0..100；band/price 无效返回 50（中性）。 */
    private fun ratioVsLowerPercent(price: Double?, band: BollBand?): Int {
        if (price == null || price <= 0.0 || band == null || band.upper <= band.lower) return 50
        return ((price - band.lower) / (band.upper - band.lower) * 100).toInt().coerceIn(0, 100)
    }

    private fun mapLlmHttpError(code: Int): String = when (code) {
        401, 403 -> "API key 无效"
        429 -> "请求过频，稍后重试"
        else -> "分析失败，请重试"
    }
}
