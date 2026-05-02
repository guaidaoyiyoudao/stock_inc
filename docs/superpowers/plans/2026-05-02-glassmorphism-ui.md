# Glassmorphism UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply glassmorphism styling (translucent surfaces with subtle borders) across the entire StockDividend app UI.

**Architecture:** Theme-level changes in Color.kt/Theme.kt propagate translucent surface colors through MaterialTheme tokens to all composables. Components that use custom container colors get updated individually. Each screen wraps content in a gradient background composable.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose BOM 2024.12.01, Material3 1.3.1

**Verification:** `./gradlew assembleDebug` compiles successfully after all changes.

---

### Task 1: Theme - Colors & Shapes

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/theme/Shape.kt`

- [ ] **Step 1: Add glass surface and border colors to Color.kt**

Replace the entire file:

```kotlin
package com.stock.dividend.ui.theme

import androidx.compose.ui.graphics.Color

// ── Clear Sky Finance Palette ──────────────────────────────────────

// Primary: Ocean Blue — clarity, trust, modernity
val Blue7 = Color(0xFF1E3A5F)
val Blue6 = Color(0xFF1D4ED8)
val Blue5 = Color(0xFF2563EB)
val Blue4 = Color(0xFF3B82F6)
val Blue3 = Color(0xFF60A5FA)
val Blue2 = Color(0xFF93C5FD)
val Blue1 = Color(0xFFDBEAFE)
val Blue0 = Color(0xFFEFF6FF)

// Secondary: Cool Slate — structure, depth
val Slate7 = Color(0xFF0F172A)
val Slate6 = Color(0xFF1E293B)
val Slate5 = Color(0xFF334155)
val Slate4 = Color(0xFF64748B)
val Slate3 = Color(0xFF94A3B8)
val Slate2 = Color(0xFFE2E8F0)
val Slate1 = Color(0xFFF1F5F9)

// Tertiary: Warm Gold — wealth, dividends
val Gold5 = Color(0xFF92400E)
val Gold4 = Color(0xFFB45309)
val Gold3 = Color(0xFFD97706)
val Gold2 = Color(0xFFFDE68A)
val Gold1 = Color(0xFFFEF3C7)

// Finance data colors
val FinanceRed = Color(0xFFDC2626)
val FinanceGreen = Color(0xFF059669)
val FinanceRedLight = Color(0xFFFEE2E2)
val FinanceGreenLight = Color(0xFFD1FAE5)

// Surfaces
val SurfaceBackground = Color(0xFFF8FAFC)
val SurfaceElevated = Color(0xFFFFFFFF)

// ── Glassmorphism Colors ───────────────────────────────────────────

object GlassColors {
    // Light theme glass surfaces
    val LightSurface = Color.White.copy(alpha = 0.75f)
    val LightSurfaceVariant = Color(0xFFF1F5F9).copy(alpha = 0.8f)
    val LightContainer = Color(0xFFEFF6FF).copy(alpha = 0.7f)
    val LightSecondaryContainer = Color(0xFFF1F5F9).copy(alpha = 0.7f)
    val LightBorder = Color.White.copy(alpha = 0.6f)
    val LightSurfaceBorder = Color.White.copy(alpha = 0.4f)

    // Dark theme glass surfaces
    val DarkSurface = Color(0xFF1E293B).copy(alpha = 0.75f)
    val DarkSurfaceVariant = Color(0xFF1E293B).copy(alpha = 0.7f)
    val DarkContainer = Color(0xFF1D4ED8).copy(alpha = 0.35f)
    val DarkSecondaryContainer = Color(0xFF334155).copy(alpha = 0.5f)
    val DarkBorder = Color.White.copy(alpha = 0.08f)
    val DarkSurfaceBorder = Color.White.copy(alpha = 0.06f)

    // Gradient background endpoints - light
    val LightGradientStart = Color(0xFFEFF6FF)
    val LightGradientEnd = Color(0xFFF0F4FF)

    // Gradient background endpoints - dark
    val DarkGradientStart = Color(0xFF0F172A)
    val DarkGradientEnd = Color(0xFF1A1F35)
}
```

- [ ] **Step 2: Update shapes for softer corners**

Replace Shape.kt:

```kotlin
package com.stock.dividend.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val StockShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 2: Theme - Light & Dark Schemes, GradientBackground

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/theme/Theme.kt`

- [ ] **Step 1: Rewrite Theme.kt with glassmorphism color schemes and GradientBackground composable**

Replace the entire file:

```kotlin
package com.stock.dividend.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Blue5,
    onPrimary = Color.White,
    primaryContainer = GlassColors.LightContainer,
    onPrimaryContainer = Blue7,

    secondary = Slate5,
    onSecondary = Color.White,
    secondaryContainer = GlassColors.LightSecondaryContainer,
    onSecondaryContainer = Slate7,

    tertiary = Gold3,
    onTertiary = Color.White,
    tertiaryContainer = Gold1.copy(alpha = 0.7f),
    onTertiaryContainer = Gold5,

    error = FinanceRed,
    onError = Color.White,
    errorContainer = FinanceRedLight.copy(alpha = 0.7f),
    onErrorContainer = FinanceRed,

    background = GlassColors.LightGradientStart,
    onBackground = Slate7,
    surface = GlassColors.LightSurface,
    onSurface = Slate7,
    surfaceVariant = GlassColors.LightSurfaceVariant,
    onSurfaceVariant = Slate4,
    outline = Slate3.copy(alpha = 0.5f),
    outlineVariant = Slate2.copy(alpha = 0.5f),
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue3,
    onPrimary = Blue7,
    primaryContainer = GlassColors.DarkContainer,
    onPrimaryContainer = Blue0,

    secondary = Slate3,
    onSecondary = Slate7,
    secondaryContainer = GlassColors.DarkSecondaryContainer,
    onSecondaryContainer = Slate1,

    tertiary = Gold2,
    onTertiary = Gold5,
    tertiaryContainer = Gold4.copy(alpha = 0.5f),
    onTertiaryContainer = Gold1,

    error = Color(0xFFEF5350),
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A).copy(alpha = 0.7f),
    onErrorContainer = Color(0xFFFFCDD2),

    background = GlassColors.DarkGradientStart,
    onBackground = Slate1,
    surface = GlassColors.DarkSurface,
    onSurface = Slate1,
    surfaceVariant = GlassColors.DarkSurfaceVariant,
    onSurfaceVariant = Slate3,
    outline = Slate5.copy(alpha = 0.5f),
    outlineVariant = Slate6.copy(alpha = 0.5f),
)

@Composable
fun StockDividendTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StockTypography,
        shapes = StockShapes,
        content = content
    )
}

@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val colors = if (isDark) {
        listOf(GlassColors.DarkGradientStart, GlassColors.DarkGradientEnd)
    } else {
        listOf(GlassColors.LightGradientStart, GlassColors.LightGradientEnd)
    }
    Box(
        modifier = modifier.background(
            brush = Brush.verticalGradient(colors)
        )
    ) {
        content()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 3: Add Glass Card Borders to Components

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/component/StockCard.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/FireProgressCard.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/ForecastComparisonCard.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/IncomeSummaryCard.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/IncomeTimelineCard.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/IncomeBreakdownChart.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/IncomeTrendChart.kt`

For each of these files, the main Card composable needs:
1. Import `androidx.compose.foundation.BorderStroke` and `com.stock.dividend.ui.theme.GlassColors` and `isSystemInDarkTheme`
2. Add `border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder)` to Card parameters

- [ ] **Step 1: StockCard.kt — add border to Card**

In the `StockCard` composable, add the border parameter to the Card call. Replace the Card block at lines 40-48:

```kotlin
Card(
    modifier = modifier
        .fillMaxWidth()
        .clickable(onClick = onClick),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder)
)
```

Add imports at top:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 2: FireProgressCard.kt — add borders to both Card instances**

Update both Card calls (the null-target one at ~line 46 and the has-target one at ~line 99). Add border and bump elevation:

```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 3: ForecastComparisonCard.kt — add border**

Update the Card (line ~40). Add border and bump elevation:

```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 4: IncomeSummaryCard.kt — add border**

Update the Card (line ~32). Add border and bump elevation:

```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 5: IncomeTimelineCard.kt — add border**

Update the Card (line ~50). Add border:

```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 6: IncomeBreakdownChart.kt — add border**

Update the Card (line ~87). Add border and bump elevation:

```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Also update the donut center hole to use surface color with alpha (line ~142). Replace:
```kotlin
drawCircle(
    color = surfaceColor.copy(alpha = 0.85f),
    radius = holeRadius,
    center = center
)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 7: IncomeTrendChart.kt — add border**

Update the Card (line ~53). Add border and bump elevation:

```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 8: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 4: Glass-stylize Remaining Components

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/component/DividendSummaryCard.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/AchievementCard.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/EmptyStateView.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/component/YearSelector.kt`

- [ ] **Step 1: DividendSummaryCard.kt — translucent gradient + border**

Replace the Card block (the whole Card composable call from line 41-48) and the Box background (lines 50-60). The outer Card becomes translucent glass, inner Box becomes the card background:

```kotlin
Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    colors = CardDefaults.cardColors(
        containerColor = Color(0xFF2563EB).copy(alpha = if (isSystemInDarkTheme()) 0.45f else 0.75f)
    ),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    ) {
```

Update the decorative circles to be slightly more visible:
- Line 69: `Color.White.copy(alpha = 0.1f)` (was 0.06f)
- Line 77: `Color.White.copy(alpha = 0.07f)` (was 0.04f)

Update the bottom stats container background (line 153):
```kotlin
Color.White.copy(alpha = 0.15f)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 2: AchievementCard.kt — glass styling**

Update unlocked Card (line ~35-42) to use glass surface:

```kotlin
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
    border = if (item.unlocked) BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder)
    else null
)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
```

- [ ] **Step 3: EmptyStateView.kt — translucent concentric circles**

Update the three concentric circles (lines 54-65). Increase alpha values for glassier look:

```kotlin
Box(
    modifier = Modifier
        .size(120.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
)
Box(
    modifier = Modifier
        .size(80.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
)
```

Add border to the inner box (line 66-72):
```kotlin
Box(
    modifier = Modifier
        .size(48.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(
            brush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            )
        ),
    contentAlignment = Alignment.Center
)
```

Add import:
```kotlin
import androidx.compose.ui.graphics.Brush
```

- [ ] **Step 4: YearSelector.kt — translucent chip backgrounds**

Update FilterChipDefaults.colors (line 47-50) to be more translucent:

```kotlin
colors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
)
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 5: Screen Gradient Backgrounds + Screen Card Borders

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/AddStockScreen.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/StockDetailScreen.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/EditHoldingScreen.kt`
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/FireGoalSetupScreen.kt`

- [ ] **Step 1: HomeScreen.kt — wrap in GradientBackground**

In the `HomeScreen` composable, wrap the Scaffold content in GradientBackground. Replace lines 155-159 (the Column inside Scaffold):

```kotlin
) { padding ->
    GradientBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
```

Close the GradientBackground before the closing brace of Scaffold (add `}` before line 294 which currently has `}`).

Also update `WatchlistContent` - the SwipeToDismissStockItem Card already uses StockCard which has glass styling, so no change needed.

Update the TabRow to be slightly translucent by wrapping it:
```kotlin
if (uiState.stocks.isNotEmpty()) {
    TabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        indicator = { tabPositions ->
            SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex])
            )
        }
    )
```

Add import at top:
```kotlin
import com.stock.dividend.ui.theme.GradientBackground
```

- [ ] **Step 2: AddStockScreen.kt — wrap in GradientBackground**

Wrap the Column content inside Scaffold. Replace line 90-95:

```kotlin
) { padding ->
    GradientBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
```

Close before the Scaffold closing brace. Add `StockSearchItem` Card border: update lines 408-416:

```kotlin
Card(
    modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    ),
    border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder)
)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
import com.stock.dividend.ui.theme.GradientBackground
```

- [ ] **Step 3: StockDetailScreen.kt — wrap in GradientBackground, add borders**

Wrap the scaffold content in GradientBackground. Replace lines 102-224 area:

```kotlin
) { padding ->
    GradientBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        if (uiState.isLoading) {
```

Close GradientBackground before Scaffold closing.

Add border to HoldingInfoBanner Card (line ~229):
```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add border to ForecastMainCard (line ~309):
```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add border to DividendRecordCard (line ~352):
```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
import com.stock.dividend.ui.theme.GradientBackground
```

- [ ] **Step 4: EditHoldingScreen.kt — wrap in GradientBackground, add card borders**

Wrap scaffold content in GradientBackground. Replace line 93-98:

```kotlin
) { padding ->
    GradientBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
```

Close GradientBackground before Scaffold closing.

Add border to stock info Card (line ~104):
```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add border to holdings summary Card (line ~151):
```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add border to TransactionCard (line ~309):
```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
import com.stock.dividend.ui.theme.GradientBackground
```

- [ ] **Step 5: FireGoalSetupScreen.kt — wrap in GradientBackground, add card borders**

Wrap scaffold content in GradientBackground. Replace lines 77-82:

```kotlin
) { padding ->
    GradientBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
```

Close GradientBackground before Scaffold closing.

Add border to the goal input Card (line ~84):
```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
```

Add border to delete goal Card (line ~131):
```kotlin
border = BorderStroke(1.dp, if (isSystemInDarkTheme()) GlassColors.DarkSurfaceBorder else GlassColors.LightSurfaceBorder),
```

Add imports:
```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.stock.dividend.ui.theme.GlassColors
import com.stock.dividend.ui.theme.GradientBackground
```

- [ ] **Step 6: Full build verification**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL
