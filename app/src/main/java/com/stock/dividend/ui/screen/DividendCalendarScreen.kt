package com.stock.dividend.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppCardDefaults
import com.stock.dividend.ui.component.FinanceMetric
import com.stock.dividend.ui.component.FinanceStatusTone
import com.stock.dividend.ui.component.StatusPill
import com.stock.dividend.viewmodel.DividendCalendarEvent
import com.stock.dividend.viewmodel.DividendCalendarDay
import com.stock.dividend.viewmodel.DividendCalendarFilter
import com.stock.dividend.viewmodel.DividendCalendarUiState
import com.stock.dividend.viewmodel.DividendCalendarViewModel
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DividendCalendarScreen(
    viewModel: DividendCalendarViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 全局刷新注册
    registerTabRefresh(
        refresh = { viewModel.refreshDividends() },
        isRefreshing = state.isRefreshing
    )

    // 嵌入「股息收入」二级 Tab 后，外层 MainScaffold 已提供 content padding，
    // 这里直接渲染内容，避免嵌套 Scaffold 造成顶部 inset 重复叠加。
    DividendCalendarContent(
        state = state,
        onFilterChanged = viewModel::onFilterChanged,
        onPreviousMonth = viewModel::showPreviousMonth,
        onNextMonth = viewModel::showNextMonth,
        onDateSelected = viewModel::onDateSelected,
        onVisibleMonthChanged = viewModel::onVisibleMonthChanged,
        onGoToToday = viewModel::goToToday,
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DividendCalendarContent(
    state: DividendCalendarUiState,
    onFilterChanged: (DividendCalendarFilter) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDateSelected: (String) -> Unit,
    onVisibleMonthChanged: (String) -> Unit,
    onGoToToday: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppCardDefaults.PageHorizontalPadding,
            top = 12.dp,
            end = AppCardDefaults.PageHorizontalPadding,
            bottom = AppCardDefaults.BottomNavigationPadding
        ),
        verticalArrangement = Arrangement.spacedBy(AppCardDefaults.SectionSpacing)
    ) {
        item {
            CalendarSummaryCard(state = state)
        }

        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DividendCalendarFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = state.selectedFilter == filter,
                        onClick = { onFilterChanged(filter) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = DividendCalendarFilter.entries.size
                        )
                    ) {
                        Text(filter.label)
                    }
                }
            }
        }

        item {
            CalendarMonthCard(
                state = state,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onVisibleMonthChanged = onVisibleMonthChanged,
                onGoToToday = onGoToToday,
                onDateSelected = onDateSelected
            )
        }

        val listEvents = if (state.selectedFilter == DividendCalendarFilter.YEAR) {
            state.events
        } else {
            state.selectedDateEvents
        }

        item {
            EventListHeader(state = state, eventCount = listEvents.size)
        }

        if (listEvents.isEmpty()) {
            item {
                EmptyEventListCard(filter = state.selectedFilter)
            }
        } else {
            listEvents.forEach { event ->
                item(key = event.id) {
                    DividendCalendarEventCard(event = event)
                }
            }
        }
    }
}

@Composable
private fun CalendarSummaryCard(
    state: DividendCalendarUiState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = AppCardDefaults.summaryCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(AppCardDefaults.SummaryPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "分红日历",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FinanceMetric(
                    label = "${state.selectedFilter.label}事件",
                    value = "${state.events.size} 条",
                    modifier = Modifier.weight(1f)
                )
                FinanceMetric(
                    label = "预计金额",
                    value = formatAmount(state.totalEstimatedAmount),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun CalendarMonthCard(
    state: DividendCalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onVisibleMonthChanged: (String) -> Unit,
    onGoToToday: () -> Unit,
    onDateSelected: (String) -> Unit
) {
    var showMonthPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = AppCardDefaults.listCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上个月"
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { showMonthPicker = true }) {
                        Text(
                            text = state.visibleMonthLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = onGoToToday) {
                        Text("今天")
                    }
                }
                IconButton(onClick = onNextMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下个月"
                    )
                }
            }

            CalendarGrid(
                days = state.calendarDays,
                onDateSelected = onDateSelected
            )
        }
    }

    if (showMonthPicker) {
        MonthYearPickerDialog(
            initialMonth = state.visibleMonth,
            onDismiss = { showMonthPicker = false },
            onConfirm = { month ->
                onVisibleMonthChanged(month)
                showMonthPicker = false
            }
        )
    }
}

@Composable
private fun MonthYearPickerDialog(
    initialMonth: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialYearMonth = remember(initialMonth) {
        runCatching { YearMonth.parse(initialMonth) }.getOrDefault(YearMonth.now())
    }
    var selectedYear by remember(initialMonth) { mutableIntStateOf(initialYearMonth.year) }
    var selectedMonth by remember(initialMonth) { mutableIntStateOf(initialYearMonth.monthValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择年月") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedYear -= 1 }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "上一年"
                        )
                    }
                    Text(
                        text = "${selectedYear}年",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = { selectedYear += 1 }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "下一年"
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..12).chunked(4).forEach { rowMonths ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowMonths.forEach { month ->
                                val selected = selectedMonth == month
                                TextButton(
                                    onClick = { selectedMonth = month },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "${month}月",
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm("%04d-%02d".format(selectedYear, selectedMonth))
                }
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CalendarGrid(
    days: List<DividendCalendarDay>,
    onDateSelected: (String) -> Unit
) {
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            weekLabels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        days.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                week.forEach { day ->
                    CalendarDayCell(
                        day = day,
                        onClick = { onDateSelected(day.date) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: DividendCalendarDay,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        day.isSelected -> MaterialTheme.colorScheme.primaryContainer
        day.isToday -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        day.isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        day.isCurrentMonth -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .height(maxWidth)
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = day.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = if (day.isSelected || day.hasEvents) FontWeight.SemiBold else FontWeight.Normal
            )
            if (day.hasEvents) {
                Box(
                    modifier = Modifier
                        .size(if (day.eventCount > 1) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            } else {
                Spacer(modifier = Modifier.height(7.dp))
            }
        }
    }
}

@Composable
private fun EventListHeader(
    state: DividendCalendarUiState,
    eventCount: Int
) {
    val title = if (state.selectedFilter == DividendCalendarFilter.YEAR) {
        "${state.visibleMonth.substringBefore("-")}年分红事件"
    } else {
        state.selectedDate
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$eventCount 条",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmptyEventListCard(filter: DividendCalendarFilter) {
    val message = if (filter == DividendCalendarFilter.YEAR) {
        "本年没有分红事件"
    } else {
        "当天没有分红事件"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = AppCardDefaults.listCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DividendCalendarEventCard(event: DividendCalendarEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = AppCardDefaults.listCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppCardDefaults.ListPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = event.stockName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatAmount(event.estimatedAmount),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${event.eventDate} · ${event.stockCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(text = event.eventType, tone = FinanceStatusTone.Warning)
                    event.planStatus?.takeIf { it.isNotBlank() }?.let { status ->
                        StatusPill(text = status, tone = FinanceStatusTone.Neutral)
                    }
                    Text(
                        text = "每股 ${formatAmount(event.cashPerShare)} · ${event.shares} 股",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatAmount(value: Double): String = "¥%.2f".format(value)
