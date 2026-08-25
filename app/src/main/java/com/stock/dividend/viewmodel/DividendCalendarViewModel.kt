package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class DividendCalendarFilter(val label: String) {
    MONTH("本月"),
    YEAR("本年"),
    FUTURE("未来"),
    HISTORY("历史")
}

enum class DividendCalendarDateType {
    EX_DIVIDEND,
    RECORD,
    PLAN_NOTICE,
    REPORT
}

@Stable
data class DividendCalendarEvent(
    val id: String,
    val stockCode: String,
    val stockName: String,
    val eventDate: String,
    val eventType: String,
    val dateType: DividendCalendarDateType,
    val cashPerShare: Double,
    val shares: Int,
    val estimatedAmount: Double,
    val planStatus: String?
)

@Stable
data class DividendCalendarMonthGroup(
    val month: String,
    val label: String,
    val totalEstimatedAmount: Double,
    val events: List<DividendCalendarEvent>
)

@Stable
data class DividendCalendarDay(
    val date: String,
    val dayOfMonth: Int,
    val isCurrentMonth: Boolean,
    val isSelected: Boolean,
    val isToday: Boolean,
    val hasEvents: Boolean,
    val eventCount: Int
)

@Stable
data class DividendCalendarUiState(
    val selectedFilter: DividendCalendarFilter = DividendCalendarFilter.MONTH,
    val visibleMonth: String = YearMonth.now().toString(),
    val visibleMonthLabel: String = formatMonthLabel(YearMonth.now().toString()),
    val selectedDate: String = LocalDate.now().toString(),
    val events: List<DividendCalendarEvent> = emptyList(),
    val monthGroups: List<DividendCalendarMonthGroup> = emptyList(),
    val calendarDays: List<DividendCalendarDay> = emptyList(),
    val selectedDateEvents: List<DividendCalendarEvent> = emptyList(),
    val isRefreshing: Boolean = false,
    val totalEstimatedAmount: Double = 0.0
)

@HiltViewModel
class DividendCalendarViewModel @Inject constructor(
    private val marketDataPlane: MarketDataPlane
) : ViewModel() {

    private val selectedFilter = MutableStateFlow(DividendCalendarFilter.MONTH)
    private val visibleMonth = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val isRefreshing = MutableStateFlow(false)
    private var latestStocks: List<StockEntity> = emptyList()
    private var latestEvents: List<DividendCalendarEvent> = emptyList()

    val uiState: StateFlow<DividendCalendarUiState> = combine(
        combine(
            marketDataPlane.observeAllDividends(),
            marketDataPlane.observeAllStocks(),
            selectedFilter,
            visibleMonth,
            selectedDate
        ) { dividends, stocks, filter, month, date ->
            latestStocks = stocks
            val allEvents = buildEvents(dividends, stocks)
            latestEvents = allEvents
            val filteredEvents = filterEvents(allEvents, filter, month)
            val groups = groupEventsByMonth(filteredEvents)
            val selectedDateText = date.toString()

            DividendCalendarUiState(
                selectedFilter = filter,
                visibleMonth = month.toString(),
                visibleMonthLabel = formatMonthLabel(month.toString()),
                selectedDate = selectedDateText,
                events = filteredEvents,
                monthGroups = groups,
                calendarDays = buildCalendarDays(month, date, filteredEvents),
                selectedDateEvents = filteredEvents.filter { it.eventDate == selectedDateText },
                totalEstimatedAmount = filteredEvents.sumOf { it.estimatedAmount }
            )
        },
        isRefreshing
    ) { state, refreshing ->
        state.copy(isRefreshing = refreshing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DividendCalendarUiState()
    )

    fun onFilterChanged(filter: DividendCalendarFilter) {
        selectedFilter.value = filter
        val targetDate = targetDateForFilter(filter, latestEvents) ?: return
        selectedDate.value = targetDate
        visibleMonth.value = YearMonth.from(targetDate)
    }

    fun onVisibleMonthChanged(month: String) {
        val parsed = runCatching { YearMonth.parse(month) }.getOrNull() ?: return
        visibleMonth.value = parsed
        selectedDate.value = parsed.atDay(1)
    }

    fun showPreviousMonth() {
        val month = visibleMonth.value.minusMonths(1)
        visibleMonth.value = month
        selectedDate.value = month.atDay(1)
    }

    fun showNextMonth() {
        val month = visibleMonth.value.plusMonths(1)
        visibleMonth.value = month
        selectedDate.value = month.atDay(1)
    }

    fun onDateSelected(date: String) {
        val parsed = date.toLocalDateOrNull() ?: return
        selectedDate.value = parsed
        visibleMonth.value = YearMonth.from(parsed)
    }

    fun goToToday() {
        val today = LocalDate.now()
        selectedDate.value = today
        visibleMonth.value = YearMonth.from(today)
    }

    fun refreshDividends() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            latestStocks.forEach { stock ->
                try {
                    marketDataPlane.refreshDividends(stock.code)
                } catch (_: Exception) { /* 单股失败不中断其他股的刷新 */ }
            }
            isRefreshing.value = false
        }
    }

    private fun buildEvents(
        dividends: List<DividendEntity>,
        stocks: List<StockEntity>
    ): List<DividendCalendarEvent> {
        val stocksByCode = stocks.associateBy { it.code }
        return dividends.mapNotNull { dividend ->
            val stock = stocksByCode[dividend.stockCode] ?: return@mapNotNull null
            val eventDate = (
                dividend.exDividendDate
                    ?: dividend.recordDate
                    ?: return@mapNotNull null
                ).toDateOnly()
            val dateType = when {
                dividend.exDividendDate != null -> DividendCalendarDateType.EX_DIVIDEND
                else -> DividendCalendarDateType.RECORD
            }
            val eventType = when (dateType) {
                DividendCalendarDateType.EX_DIVIDEND -> "除权除息"
                DividendCalendarDateType.RECORD -> "股权登记"
                DividendCalendarDateType.PLAN_NOTICE -> "预案"
                DividendCalendarDateType.REPORT -> "公告"
            }

            DividendCalendarEvent(
                id = dividend.id,
                stockCode = dividend.stockCode,
                stockName = stock.name,
                eventDate = eventDate,
                eventType = eventType,
                dateType = dateType,
                cashPerShare = dividend.cashPerShare,
                shares = stock.shares,
                estimatedAmount = dividend.cashPerShare * stock.shares,
                planStatus = dividend.planStatus
            )
        }.sortedBy { it.eventDate }
    }

    private fun filterEvents(
        events: List<DividendCalendarEvent>,
        filter: DividendCalendarFilter,
        visibleMonth: YearMonth
    ): List<DividendCalendarEvent> {
        val today = LocalDate.now()
        return events.filter { event ->
            val date = event.eventDate.toLocalDateOrNull() ?: return@filter false
            when (filter) {
                DividendCalendarFilter.MONTH -> YearMonth.from(date) == visibleMonth
                DividendCalendarFilter.YEAR -> date.year == visibleMonth.year
                DividendCalendarFilter.FUTURE ->
                    event.dateType == DividendCalendarDateType.EX_DIVIDEND && !date.isBefore(today)
                DividendCalendarFilter.HISTORY -> date.isBefore(today)
            }
        }
    }

    private fun targetDateForFilter(
        filter: DividendCalendarFilter,
        events: List<DividendCalendarEvent>
    ): LocalDate? {
        val today = LocalDate.now()
        return when (filter) {
            DividendCalendarFilter.MONTH -> today
            DividendCalendarFilter.YEAR -> today
            DividendCalendarFilter.FUTURE -> filterEvents(events, filter, YearMonth.from(today))
                .mapNotNull { it.eventDate.toLocalDateOrNull() }
                .minOrNull()
            DividendCalendarFilter.HISTORY -> filterEvents(events, filter, YearMonth.from(today))
                .mapNotNull { it.eventDate.toLocalDateOrNull() }
                .maxOrNull()
        }
    }

    private fun groupEventsByMonth(events: List<DividendCalendarEvent>): List<DividendCalendarMonthGroup> {
        return events
            .groupBy { it.eventDate.substring(0, 7) }
            .map { (month, monthEvents) ->
                DividendCalendarMonthGroup(
                    month = month,
                    label = formatMonthLabel(month),
                    totalEstimatedAmount = monthEvents.sumOf { it.estimatedAmount },
                    events = monthEvents
                )
            }
            .sortedBy { it.month }
    }

    private fun buildCalendarDays(
        month: YearMonth,
        selectedDate: LocalDate,
        events: List<DividendCalendarEvent>
    ): List<DividendCalendarDay> {
        val firstDay = month.atDay(1)
        val gridStart = firstDay.minusDays((firstDay.dayOfWeek.value - 1).toLong())
        val today = LocalDate.now()
        val eventCounts = events.groupingBy { it.eventDate }.eachCount()
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE

        return (0 until 42).map { offset ->
            val date = gridStart.plusDays(offset.toLong())
            val dateText = date.format(formatter)
            val count = eventCounts[dateText] ?: 0
            DividendCalendarDay(
                date = dateText,
                dayOfMonth = date.dayOfMonth,
                isCurrentMonth = YearMonth.from(date) == month,
                isSelected = date == selectedDate,
                isToday = date == today,
                hasEvents = count > 0,
                eventCount = count
            )
        }
    }

    private fun String.toLocalDateOrNull(): LocalDate? =
        runCatching { LocalDate.parse(toDateOnly()) }.getOrNull()

    private fun String.toDateOnly(): String =
        substringBefore("T").substringBefore(" ")
}

private fun formatMonthLabel(month: String): String {
    val parts = month.split("-")
    return if (parts.size == 2) "${parts[0]}年${parts[1].toIntOrNull() ?: parts[1]}月" else month
}
