package com.stock.dividend.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stock.dividend.data.repository.MoneyFormatter
import com.stock.dividend.data.repository.PercentFormatter
import com.stock.dividend.ui.theme.LocalExtendedColors
import com.stock.dividend.ui.theme.Motion
import com.stock.dividend.ui.theme.tabularNumberStyle
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun FireProgressCard(
    targetAmount: Double?,
    forecastTotal: Double,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalExtendedColors.current
    val coveragePercent = (progress ?: 0f).coerceIn(0f, 999f)
    val coverageProgress = (coveragePercent / 100f).coerceIn(0f, 1f)
    val coveredAmount = (targetAmount ?: 0.0) * coverageProgress
    val gapAmount = ((targetAmount ?: 0.0) - coveredAmount).coerceAtLeast(0.0)
    val achieved = coveragePercent >= 100f
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    if (targetAmount == null || progress == null) {
        AppCard(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            tone = AppCardTone.Surface,
            border = cardBorder,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "支出",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "生活支出",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "去设置",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        AppCard(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            tone = AppCardTone.Surface,
            border = cardBorder,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "生活支出",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PercentText(
                        value = coveragePercent.toDouble(),
                        decimals = 1,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (achieved) ext.positive else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 水波进度（借鉴 WaveLoading 模式）：波面精确停在 coverageProgress 处
                WaveProgress(
                    progress = coverageProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    color = if (achieved) ext.positive else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已覆盖 ${MoneyFormatter.compact(coveredAmount)}",
                        style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (gapAmount > 0.0) "差 ${MoneyFormatter.compact(gapAmount)}" else "已覆盖",
                        style = MaterialTheme.typography.labelSmall.merge(tabularNumberStyle),
                        color = if (gapAmount > 0.0) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            ext.positive
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "收入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = MoneyFormatter.compact(forecastTotal),
                            style = MaterialTheme.typography.titleSmall.merge(tabularNumberStyle),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "支出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = MoneyFormatter.compact(targetAmount),
                            style = MaterialTheme.typography.titleSmall.merge(tabularNumberStyle),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 水波进度条（借鉴 WaveLoading 模式）：两条正弦波错相无限水平滚动，波面精确停在 [progress] 处。
 *
 * 数据准确性：进度值经 animateFloatAsState 平滑过渡，落点恒为传入的精确 progress；
 * 波动仅是波面附近的正弦扰动，不改变水位含义。
 */
@Composable
private fun WaveProgress(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val waterLevel by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(Motion.DurationLong, easing = Motion.EmphasizedDecelerate),
        label = "waterLevel",
    )
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )
    val dimColor = color.copy(alpha = 0.45f)

    Canvas(
        modifier = modifier.clip(MaterialTheme.shapes.extraSmall),
    ) {
        drawRect(color = trackColor)

        val surfaceY = size.height * (1f - waterLevel)
        val amplitude = 2.5.dp.toPx()
        val wavelength = size.width / 1.6f
        val step = 8.dp.toPx()

        fun wavePath(phaseShift: Float): Path = Path().apply {
            moveTo(-wavelength, surfaceY)
            var x = -wavelength
            while (x <= size.width + step) {
                val y = surfaceY + sin((x + phase + phaseShift) / wavelength * 2f * PI.toFloat()) * amplitude
                lineTo(x, y)
                x += step
            }
            lineTo(size.width, size.height)
            lineTo(-wavelength, size.height)
            close()
        }

        drawPath(path = wavePath(0f), color = dimColor)
        drawPath(path = wavePath(wavelength / 2f), color = color)
    }
}
