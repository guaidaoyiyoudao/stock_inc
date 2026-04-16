package com.stock.dividend.ui.component

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stock.dividend.ui.theme.Gold3
import com.stock.dividend.ui.theme.Jade2
import com.stock.dividend.ui.theme.Jade3
import com.stock.dividend.ui.theme.Jade4
import com.stock.dividend.ui.theme.Jade5

@Composable
fun FireProgressCard(
    targetAmount: Double?,
    forecastTotal: Double,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (targetAmount != null) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                Jade4
            }
        )
    ) {
        if (targetAmount == null || progress == null) {
            // No goal set — show setup prompt
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Jade4)
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Gold3)
                    )
                    Text(
                        text = "设置 FIRE 退休目标",
                        style = MaterialTheme.typography.labelLarge,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "点击设置 →",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            // Goal set — show progress
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Gold3)
                    )
                    Text(
                        text = "FIRE 退休进度",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (progress >= 100f) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "已达标",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Jade3
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progress bar
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (progress >= 100f) Jade3 else Jade4,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Amounts row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "年股息收入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = formatAmount(forecastTotal),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "目标年支出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = formatAmount(targetAmount),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Percentage
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("%.1f%%".format(progress))
                        }
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (progress >= 100f) Jade3 else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun formatAmount(amount: Double): String {
    return when {
        amount >= 100_000_000 -> "¥%.2f亿".format(amount / 100_000_000)
        amount >= 10_000 -> "¥%.2f万".format(amount / 10_000)
        else -> "¥%.2f".format(amount)
    }
}
