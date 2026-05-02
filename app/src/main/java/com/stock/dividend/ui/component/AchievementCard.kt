package com.stock.dividend.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.stock.dividend.ui.theme.GlassColors
import com.stock.dividend.viewmodel.AchievementCategory
import com.stock.dividend.viewmodel.AchievementItem
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AchievementCard(
    item: AchievementItem,
    modifier: Modifier = Modifier
) {
    val alpha = if (item.unlocked) 1f else 0.4f

    Card(
        modifier = modifier.alpha(alpha),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = if (item.unlocked) 2.dp else 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.unlocked) {
                if (isSystemInDarkTheme()) GlassColors.DarkContainer
                else GlassColors.LightContainer
            } else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (item.unlocked) BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder) else null
    ) {
        Column(
            modifier = Modifier
                .height(140.dp)
                .width(160.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = item.def.icon,
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.def.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (item.unlocked) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = item.def.description,
                style = MaterialTheme.typography.labelSmall,
                color = if (item.unlocked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(120.dp)
            )

            if (item.unlocked && item.unlockedAt != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date(item.unlockedAt))
                Text(
                    text = date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun CategoryHeader(
    category: AchievementCategory,
    achievements: List<AchievementItem>,
    modifier: Modifier = Modifier
) {
    val unlockedCount = achievements.count { it.unlocked }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.icon,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = category.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "已解锁 $unlockedCount/${achievements.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = category.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun CategoryGrid(
    achievements: List<AchievementItem>,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false,
        modifier = modifier.height(150.dp * ((achievements.size + 1) / 2))
    ) {
        items(items = achievements, key = { it.def.id }) { item ->
            AchievementCard(item = item)
        }
    }
}

@Composable
fun CategorizedAchievementList(
    achievements: List<AchievementItem>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        for (category in AchievementCategory.entries) {
            val categoryAchievements = achievements.filter { it.def.category == category }
            if (categoryAchievements.isNotEmpty()) {
                item(key = "header_${category.id}") {
                    CategoryHeader(
                        category = category,
                        achievements = categoryAchievements
                    )
                }
                item(key = "grid_${category.id}") {
                    CategoryGrid(achievements = categoryAchievements)
                }
            }
        }
    }
}
