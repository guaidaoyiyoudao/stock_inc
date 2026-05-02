# Achievement Category Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Group the flat achievement grid into categorized sections with headers showing icon, title, description, and unlock progress.

**Architecture:** Add an `AchievementCategory` enum alongside `AchievementDef`, with each achievement referencing its category. UI groups achievements by category using `CategorySection` composable. No database or ViewModel changes.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Material Design 3

---

### Task 1: Create AchievementCategory enum

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/AchievementCategory.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.stock.dividend.viewmodel

enum class AchievementCategory(
    val id: String,
    val title: String,
    val description: String,
    val icon: String
) {
    INCOME_MILESTONE("income_milestone", "收入里程碑", "迈向财务自由之路", "💰"),
    INVESTMENT_STRATEGY("investment_strategy", "投资策略", "构建多元化组合", "📊"),
    LONG_TERM_COMMITMENT("long_term_commitment", "长期坚持", "时间是最好的朋友", "⏳")
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AchievementCategory.kt
git commit -m "feat: add AchievementCategory enum for grouping achievements"
```

---

### Task 2: Add category field to AchievementDef

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/AchievementDef.kt`

- [ ] **Step 1: Add category field to enum constructor and each entry**

The full updated file:

```kotlin
package com.stock.dividend.viewmodel

enum class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val category: AchievementCategory
) {
    FIRST_DIVIDEND("first_dividend", "首次分红", "收到第一笔股息收入", "🌱", AchievementCategory.INCOME_MILESTONE),
    INCOME_1K("income_1k", "收入破千", "单年股息收入达到1,000元", "🌿", AchievementCategory.INCOME_MILESTONE),
    INCOME_10K("income_10k", "收入破万", "单年股息收入达到10,000元", "🌳", AchievementCategory.INCOME_MILESTONE),
    INCOME_100K("income_100k", "收入十万", "单年股息收入达到100,000元", "🏔️", AchievementCategory.INCOME_MILESTONE),
    PORTFOLIO_START("portfolio_start", "持仓起步", "开始关注第一只股票", "🚩", AchievementCategory.INVESTMENT_STRATEGY),
    DIVERSIFY_5("diversify_5", "分散投资", "同时持有5只以上股票", "🛡️", AchievementCategory.INVESTMENT_STRATEGY),
    HOLD_1Y("hold_1y", "坚持持有", "最早添加的股票持有满一年", "💎", AchievementCategory.LONG_TERM_COMMITMENT),
    STREAK_3Y("streak_3y", "连年分红", "连续3年有股息收入", "❄️", AchievementCategory.LONG_TERM_COMMITMENT);
}
```

- [ ] **Step 2: Verify existing tests still compile and pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.stock.dividend.viewmodel.AchievementCheckerTest"`
Expected: All 12 tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/AchievementDef.kt
git commit -m "feat: add category field to AchievementDef enum"
```

---

### Task 3: Replace AchievementGrid with CategorizedAchievementList

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/component/AchievementCard.kt`

- [ ] **Step 1: Add CategorySection composable and replace AchievementGrid with CategorizedAchievementList**

Add these imports to the existing import block:

```kotlin
import com.stock.dividend.viewmodel.AchievementCategory
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
```

Add the `CategorySection` composable after `AchievementCard` (before the existing `AchievementGrid`):

```kotlin
@Composable
fun CategorySection(
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

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(((achievements.size + 1) / 2) * 150.dp)
        ) {
            items(items = achievements, key = { it.def.id }) { item ->
                AchievementCard(item = item)
            }
        }
    }
}
```

Replace `AchievementGrid` with `CategorizedAchievementList`:

```kotlin
@Composable
fun CategorizedAchievementList(
    achievements: List<AchievementItem>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        for (category in AchievementCategory.entries) {
            val categoryAchievements = achievements.filter { it.def.category == category }
            if (categoryAchievements.isNotEmpty()) {
                CategorySection(
                    category = category,
                    achievements = categoryAchievements
                )
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/component/AchievementCard.kt
git commit -m "feat: replace flat AchievementGrid with CategorizedAchievementList"
```

---

### Task 4: Update HomeScreen call site

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`

- [ ] **Step 1: Update import and function call**

Change the import on line 55 from:
```kotlin
import com.stock.dividend.ui.component.AchievementGrid
```
to:
```kotlin
import com.stock.dividend.ui.component.CategorizedAchievementList
```

Change the call on line 457 from:
```kotlin
AchievementGrid(achievements = state.achievements)
```
to:
```kotlin
CategorizedAchievementList(achievements = state.achievements)
```

- [ ] **Step 2: Build the project to verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
git commit -m "feat: update HomeScreen to use CategorizedAchievementList"
```
