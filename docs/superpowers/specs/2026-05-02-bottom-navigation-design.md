# Bottom Navigation Redesign Spec

## Overview
Replace the top TabRow in HomeScreen with a bottom NavigationBar using nested NavHost pattern, similar to 木木记账.

## Architecture
Nested NavHost: Root NavHost ("main") → MainScaffold (Scaffold + NavigationBar + nested NavHost) → 3 tab destinations (watchlist/income/achievements). Detail pages push on top of their respective tab's back stack.

## Navigation Structure
```
Root NavHost (start = "main")
  └─ MainScaffold
       ├─ NavigationBar (3 items: 持仓/收入/成就)
       ├─ FAB (context-aware per tab)
       └─ Tab NavHost
            ├─ watchlist → WatchlistScreen
            │    ├─ addStock
            │    ├─ stockDetail/{code}
            │    └─ editHolding/{code}
            ├─ income → IncomeScreen
            └─ achievements → AchievementScreen
       └─ fireGoalSetup (shared route)
```

## Files Changed
- AppNavigation.kt: Complete restructure
- HomeScreen.kt: Split into WatchlistScreen, IncomeScreen, AchievementScreen
- New: MainScaffold.kt
- MainActivity.kt: Minor adjustments

## Icons
- 持仓: Icons.Filled.AccountBalance
- 收入: Icons.Filled.TrendingUp
- 成就: Icons.Filled.EmojiEvents

## Style
Glassmorphism NavigationBar: translucent background + top border
