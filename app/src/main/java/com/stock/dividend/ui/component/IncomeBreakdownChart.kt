package com.stock.dividend.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class PieSlice(
    val label: String,
    val amount: Double,
    val color: Color
)

private val PIE_COLORS = listOf(
    Color(0xFF4CAF50),
    Color(0xFF2196F3),
    Color(0xFFFF9800),
    Color(0xFF9C27B0),
    Color(0xFFF44336),
    Color(0xFF00BCD4),
    Color(0xFFFFEB3B),
    Color(0xFF795548),
)

@Composable
fun IncomeBreakdownChart(
    records: List<com.stock.dividend.viewmodel.DividendIncomeRecordWithStock>,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) return

    val slices = remember(records) {
        val grouped = records
            .filter { it.record.amount > 0 }
            .groupBy { it.stockName ?: "其他收入" }
            .mapValues { (_, items) -> items.sumOf { it.record.amount } }
            .entries
            .sortedByDescending { it.value }

        if (grouped.isEmpty()) emptyList()
        else {
            val total = grouped.sumOf { it.value }
            grouped.mapIndexed { index, (name, amount) ->
                PieSlice(
                    label = name,
                    amount = amount,
                    color = PIE_COLORS[index % PIE_COLORS.size]
                )
            }
        }
    }

    if (slices.isEmpty()) return

    val total = slices.sumOf { it.amount }
    val textStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = "收入构成",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val surfaceColor = MaterialTheme.colorScheme.surface
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface

                // Pie chart
                Canvas(
                    modifier = Modifier.size(120.dp)
                ) {
                    val canvasSize = min(size.width, size.height)
                    val radius = canvasSize / 2f
                    val topLeft = Offset(
                        (size.width - canvasSize) / 2f,
                        (size.height - canvasSize) / 2f
                    )
                    val arcSize = Size(canvasSize, canvasSize)

                    var startAngle = -90f
                    for (slice in slices) {
                        val sweep = (slice.amount / total * 360f).toFloat()
                        drawArc(
                            color = slice.color,
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = true,
                            topLeft = topLeft,
                            size = arcSize
                        )
                        startAngle += sweep
                    }

                    // Center hole (donut)
                    val holeRadius = radius * 0.55f
                    drawCircle(
                        color = surfaceColor,
                        radius = holeRadius,
                        center = center
                    )

                    // Center text
                    val totalText = "¥%.0f".format(total)
                    val textLayoutResult = textMeasurer.measure(
                        totalText,
                        style = textStyle.copy(fontSize = 13.sp, textAlign = TextAlign.Center)
                    )
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(
                            center.x - textLayoutResult.size.width / 2f,
                            center.y - textLayoutResult.size.height / 2f
                        ),
                        color = onSurfaceColor
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Legend
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    slices.forEach { slice ->
                        val percent = (slice.amount / total * 100)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(slice.color)
                            )
                            Text(
                                text = slice.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "%.0f%%".format(percent),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
