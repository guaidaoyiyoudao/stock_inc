package com.stock.dividend.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stock.dividend.ui.theme.FinanceGreen
import com.stock.dividend.ui.theme.FinanceRed

object AppCardDefaults {
    val ListPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    val SummaryPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
    val SectionSpacing: Dp = 10.dp
    val PageHorizontalPadding: Dp = 16.dp
    val BottomNavigationPadding: Dp = 88.dp

    @Composable
    fun listCardColors(): CardColors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)

    @Composable
    fun summaryCardColors(): CardColors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
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

        if (actionText != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                if (actionIcon != null) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null
                    )
                }
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun FinanceMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            textAlign = textAlign
        )
    }
}

enum class FinanceStatusTone {
    Positive,
    Warning,
    Negative,
    Neutral
}

@Composable
fun StatusPill(
    text: String,
    tone: FinanceStatusTone,
    modifier: Modifier = Modifier
) {
    val color = when (tone) {
        FinanceStatusTone.Positive -> FinanceGreen
        FinanceStatusTone.Warning -> MaterialTheme.colorScheme.tertiary
        FinanceStatusTone.Negative -> FinanceRed
        FinanceStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}
