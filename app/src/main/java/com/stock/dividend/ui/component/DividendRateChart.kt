package com.stock.dividend.ui.component

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.stock.dividend.data.repository.BuyThresholdStatus
import com.stock.dividend.viewmodel.DividendRatePoint

@Composable
fun DividendRateChart(
    points: List<DividendRatePoint>,
    modifier: Modifier = Modifier,
    buyThreshold: BuyThresholdStatus? = null
) {
    if (points.size < 2) return

    val latest = remember(points) { points.maxByOrNull { it.period } }
    val average = remember(points) { points.map { it.ratePercent }.average() }
    val maxPoint = remember(points) { points.maxByOrNull { it.ratePercent } }
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val grid = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val fill = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    // 买入阈值线颜色：达标用 success(tertiary 容器) 强调，否则用 outline 弱化
    val thresholdColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val reachedColor = MaterialTheme.colorScheme.primary.toArgb()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "年度分红率",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = latest?.let { "${it.period}  ${formatPercent(it.ratePercent)}" } ?: "--",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "平均",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatPercent(average),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            buyThreshold?.let { BuyThresholdPrompt(it) }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                factory = { context ->
                    LineChart(context).apply {
                        setNoDataText("暂无分红率趋势")
                        setTouchEnabled(true)
                        isDragEnabled = true
                        setPinchZoom(false)
                        isDoubleTapToZoomEnabled = false
                        setScaleEnabled(false)
                        setDrawGridBackground(false)
                        setBackgroundColor(surface)
                        setExtraOffsets(0f, 8f, 0f, 0f)
                        description.isEnabled = false
                        legend.isEnabled = false
                        axisRight.isEnabled = false
                        minOffset = 8f

                        xAxis.position = XAxis.XAxisPosition.BOTTOM
                        xAxis.granularity = 1f
                        xAxis.setLabelCount(6, false)
                        xAxis.setDrawAxisLine(false)
                        xAxis.setDrawGridLines(false)
                        xAxis.textColor = onSurfaceVariant
                        xAxis.textSize = 11f
                        xAxis.typeface = Typeface.DEFAULT_BOLD
                        xAxis.isEnabled = true

                        axisLeft.setDrawAxisLine(false)
                        axisLeft.setDrawGridLines(true)
                        axisLeft.gridColor = grid
                        axisLeft.gridLineWidth = 0.8f
                        axisLeft.textColor = onSurfaceVariant
                        axisLeft.textSize = 11f
                        axisLeft.axisMinimum = 0f
                        axisLeft.setLabelCount(5, false)
                        axisLeft.valueFormatter = PercentAxisFormatter()
                    }
                },
                update = { chart ->
                    chart.setBackgroundColor(surface)
                    chart.xAxis.textColor = onSurfaceVariant
                    chart.axisLeft.textColor = onSurfaceVariant
                    chart.axisLeft.gridColor = grid
                    chart.xAxis.valueFormatter = YearAxisFormatter(points.map { it.label })
                    chart.data = LineData(createDividendRateDataSet(points, primary, fill))
                    chart.data.setValueTextColor(onSurface)
                    chart.data.setValueTextSize(10f)
                    // 买入阈值线：国债收益率 × 倍数
                    chart.axisLeft.removeAllLimitLines()
                    val target = buyThreshold?.takeIf { it.targetYieldPercent > 0f }?.targetYieldPercent
                    if (target != null) {
                        val lineColor = if (buyThreshold?.reached == true) reachedColor else thresholdColor
                        val limitLine = LimitLine(target.toFloat(), "买入线 ${formatPercent(target)}").apply {
                            enableDashedLine(12f, 8f, 0f)
                            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
                        }
                        limitLine.lineColor = lineColor
                        limitLine.lineWidth = 1.4f
                        limitLine.textColor = lineColor
                        limitLine.textSize = 10f
                        chart.axisLeft.addLimitLine(limitLine)
                    }
                    chart.setVisibleXRangeMaximum(CHART_VISIBLE_YEAR_COUNT)
                    chart.setVisibleXRangeMinimum(2f)
                    if (points.size > CHART_VISIBLE_YEAR_COUNT) {
                        chart.moveViewToX((points.lastIndex - CHART_VISIBLE_YEAR_COUNT + 1).toFloat())
                    }
                    chart.invalidate()
                    chart.animateX(450)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 0.dp)
            ) {
                items(points, key = { it.period }) { point ->
                    DividendRatePointChip(
                        point = point,
                        isPeak = point.period == maxPoint?.period
                    )
                }
            }
        }
    }
}

@Composable
fun DividendRateFallbackCard(
    point: DividendRatePoint?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (point == null) {
                Text(
                    text = "暂无分红率趋势数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "当前分红记录未提供有效分红率，无法绘制趋势。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "${point.period} 分红率 ${formatPercent(point.ratePercent)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "仅有一个年份的有效分红率，暂不足以形成趋势。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun createDividendRateDataSet(
    points: List<DividendRatePoint>,
    primaryColor: Int,
    fillColor: Int
): LineDataSet {
    val entries = points.mapIndexed { index, point ->
        Entry(index.toFloat(), point.ratePercent.toFloat())
    }
    return LineDataSet(entries, "年度分红率").apply {
        mode = LineDataSet.Mode.CUBIC_BEZIER
        cubicIntensity = 0.18f
        color = primaryColor
        lineWidth = 3f
        setCircleColor(primaryColor)
        circleRadius = 4.2f
        circleHoleRadius = 2.2f
        circleHoleColor = android.graphics.Color.WHITE
        highLightColor = primaryColor
        highlightLineWidth = 1.2f
        setDrawHorizontalHighlightIndicator(false)
        setDrawFilled(true)
        this.fillColor = fillColor
        fillAlpha = 90
        setDrawValues(true)
        valueTextColor = primaryColor
        valueTextSize = 10f
        setHighlightEnabled(false)
        valueFormatter = PercentValueFormatter()
    }
}

@Composable
private fun DividendRatePointChip(
    point: DividendRatePoint,
    isPeak: Boolean
) {
    val containerColor = if (isPeak) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isPeak) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier
            .background(containerColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(contentColor)
        )
        Text(
            text = point.period,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
        Text(
            text = formatPercent(point.ratePercent),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private class YearAxisFormatter(
    private val labels: List<String>
) : ValueFormatter() {
    override fun getFormattedValue(value: Float): String {
        val index = value.toInt()
        return if (index in labels.indices) labels[index] else ""
    }
}

private class PercentAxisFormatter : ValueFormatter() {
    override fun getFormattedValue(value: Float): String = "${"%.1f".format(value)}%"
}

private class PercentValueFormatter : ValueFormatter() {
    override fun getFormattedValue(value: Float): String = formatPercent(value.toDouble())
}

private const val CHART_VISIBLE_YEAR_COUNT = 6f

private fun formatPercent(value: Double): String = "${"%.2f".format(value)}%"

/**
 * 买入阈值提示：基于「10Y 国债收益率 × 倍数」的目标买入股息率。
 * - 已达：绿色 chip「✓ 达到买入线 X% · 建议买入」
 * - 未达：弱化文字「当前 X% · 买入线 Y%（国债 a% × n）未达」
 * - 数据不全：仅展示「买入线 Y%（国债 a% × n）」
 */
@Composable
private fun BuyThresholdPrompt(status: BuyThresholdStatus) {
    val reached = status.reached
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer

    val (text, bg, fg) = when {
        reached == true -> Triple(
            "✓ 达到买入线 ${formatPercent(status.targetYieldPercent)} · 建议买入",
            containerColor,
            onContainerColor
        )
        reached == false -> Triple(
            "当前 ${status.currentYieldPercent?.let { formatPercent(it) } ?: "--"} · " +
                    "买入线 ${formatPercent(status.targetYieldPercent)} " +
                    "（国债 ${formatPercent(status.bondYield10Y)} × ${"%.1f".format(status.multiplier)}）未达",
            secondaryContainer,
            onSecondaryContainer
        )
        else -> Triple(
            "买入线 ${formatPercent(status.targetYieldPercent)} " +
                    "（国债 ${formatPercent(status.bondYield10Y)} × ${"%.1f".format(status.multiplier)}）",
            secondaryContainer,
            onSecondaryContainer
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = if (reached == true) FontWeight.SemiBold else FontWeight.Normal
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
}
