package com.stock.dividend.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.stock.dividend.viewmodel.IndustryGroup

/**
 * 行业配置饼图。
 *
 * 左侧画实际占比饼图（按行业市值 / 总资产），右侧画图例：行业名 + 实际% + 目标%。
 * 实际占比为 null（总资产未设）时降级为按行业市值占比，并在图例标注。
 */
@Composable
fun IndustryAllocationPieChart(
    groups: List<IndustryGroup>,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return

    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    val palette = rememberPalette(groups.size)

    // 准备饼图数据：优先用实际占比，降级用市值内部占比
    val useActual = groups.any { it.actualWeight != null }
    val entries = groups.mapIndexed { i, g ->
        val value = if (useActual) (g.actualWeight ?: 0.0) else g.holdingsMarketValue
        PieEntry(value.toFloat(), g.name)
    }
    val total = entries.sumOf { it.value.toDouble() }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AndroidView(
            modifier = Modifier
                .size(160.dp)
                .padding(4.dp),
            factory = { context ->
                PieChart(context).apply {
                    description.isEnabled = false
                    setUsePercentValues(true)
                    setDrawEntryLabels(false)
                    setHoleColor(surface)
                    holeRadius = 55f
                    transparentCircleRadius = 58f
                    rotationAngle = 0f
                    isRotationEnabled = true
                    setCenterText(if (useActual) "实际占比" else "市值占比")
                    setCenterTextSize(14f)
                    setCenterTextColor(onSurfaceVariant)
                    setNoDataText("暂无持仓")
                    setEntryLabelColor(onSurface)
                    legend.isEnabled = false
                    animateY(450)
                }
            },
            update = { chart ->
                val dataSet = PieDataSet(entries, "").apply {
                    colors = palette
                    setDrawValues(false)
                    sliceSpace = 1.5f
                    selectionShift = 6f
                }
                chart.data = PieData(dataSet).apply {
                    setValueFormatter(PercentFormatter(chart))
                    setValueTextColor(onSurface)
                    setValueTextSize(11f)
                    setDrawValues(false)
                }
                chart.setCenterText(if (useActual) "实际占比" else "市值占比")
                chart.setBackgroundColor(surface)
                chart.invalidate()
                if (entries.isNotEmpty()) chart.animateY(450)
            }
        )

        Spacer(modifier = Modifier.size(8.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            groups.forEachIndexed { i, g ->
                LegendRow(
                    color = Color(palette[i % palette.size]),
                    name = g.name,
                    actual = if (useActual) g.actualWeight else g.holdingsMarketValue.let { if (total > 0) it / total * 100.0 else 0.0 },
                    target = g.targetWeight,
                    isUnclassified = g.name == "未分类"
                )
            }
        }
    }
}

@Composable
private fun LegendRow(
    color: Color,
    name: String,
    actual: Double?,
    target: Double,
    isUnclassified: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = (actual?.let { "%.1f%%".format(it) } ?: "—") +
                            if (target > 0.0) " / 目标 %.0f%%".format(target) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 生成稳定的颜色调色板（基于 Material ColorTemplate 扩展）。 */
private fun rememberPalette(size: Int): List<Int> {
    val templates = listOf(
        ColorTemplate.MATERIAL_COLORS.toList(),
        ColorTemplate.JOYFUL_COLORS.toList(),
        ColorTemplate.LIBERTY_COLORS.toList(),
        ColorTemplate.PASTEL_COLORS.toList(),
        ColorTemplate.COLORFUL_COLORS.toList()
    ).flatten()
    return List(size.coerceAtLeast(1)) { i -> templates[i % templates.size] }
}
