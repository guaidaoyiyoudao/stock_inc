# Glassmorphism UI Redesign Spec

## Overview
Apply glassmorphism (玻璃拟态) design to the entire StockDividend Android app UI. Glassmorphism uses translucent surfaces, subtle borders, and soft shadows to create a frosted glass aesthetic.

## Implementation Approach
**Pure semi-transparent effect** — use alpha-blended surface colors + 1dp borders + enhanced shadows without actual backdrop blur (avoids RenderEffect complexity and performance cost on Android).

## Scope
- Both light and dark themes
- All 5 screens, 11 custom components, and theme files

## Changes

### 1. Theme Layer (`Color.kt`, `Theme.kt`, `Shape.kt`)
- Add `GlassSurface` colors (translucent white/dark variants)
- Add `GlassBorder` colors for card borders
- Add `GradientBackground` composable (vibrant vertical gradient)
- Light mode: background → vibrant blue-purple gradient, surface → white @ 0.75-0.85 alpha
- Dark mode: background → dark vibrant gradient, surface → dark @ 0.75-0.85 alpha
- Shapes: slightly increase radius (small 12dp, medium 16dp, large 24dp)

### 2. Screen Backgrounds
- Each screen wraps content in `GradientBackground` for vibrant backdrop
- Screens: HomeScreen, AddStockScreen, StockDetailScreen, EditHoldingScreen, FireGoalSetupScreen

### 3. Components
- All cards: add 1dp translucent border + semi-transparent surface
- `DividendSummaryCard`: translucent gradient instead of solid blue
- `StockCard`, `FireProgressCard`, etc.: glass surface styling
- `AchievementCard`: blurred when locked, bright when unlocked
- `EmptyStateView`: translucent concentric circles
- `YearSelector`: translucent FilterChip backgrounds

### 4. Not Changed
- Typography
- Vico chart library internals
- Layout structures
- Data/Navigation/ViewModel layers
