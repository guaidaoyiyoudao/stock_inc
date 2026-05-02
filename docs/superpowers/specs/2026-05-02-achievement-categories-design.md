# Achievement Category Display Design

Date: 2026-05-02

## Problem

The achievement tab displays all 8 achievements in a flat grid with no grouping. As more achievements are added, the flat list becomes harder to scan. Users cannot quickly see their progress within a specific theme (income milestones, portfolio strategy, long-term commitment).

## Approach

Add an `AchievementCategory` enum and a `category` field to `AchievementDef`. Group achievements by category in the UI with section headers showing title, icon, description, and unlock progress. No database or business logic changes required.

## Data Model

### New: `AchievementCategory` enum

```kotlin
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

### Modified: `AchievementDef`

Add `category: AchievementCategory` field to each enum entry:

| Achievement | Category |
|---|---|
| FIRST_DIVIDEND (首次分红) | INCOME_MILESTONE |
| INCOME_1K (收入破千) | INCOME_MILESTONE |
| INCOME_10K (收入破万) | INCOME_MILESTONE |
| INCOME_100K (收入十万) | INCOME_MILESTONE |
| PORTFOLIO_START (持仓起步) | INVESTMENT_STRATEGY |
| DIVERSIFY_5 (分散投资) | INVESTMENT_STRATEGY |
| HOLD_1Y (坚持持有) | LONG_TERM_COMMITMENT |
| STREAK_3Y (连年分红) | LONG_TERM_COMMITMENT |

## UI Changes

### New: `CategorySection` composable

Renders a category group with:
- Section header: icon + category title
- Progress text: "已解锁 X/Y"
- Description text (subtitle)
- 2-column grid of `AchievementCard` for achievements in this category

### Modified: `AchievementGrid` → `CategorizedAchievementList`

Replace flat grid with grouped layout. Iterates `AchievementCategory.entries`, filters achievements by category, and renders a `CategorySection` for each group.

### Unchanged: `AchievementCard`

Single card rendering remains the same.

### HomeScreen

`AchievementTabContent` updates its call from `AchievementGrid(achievements)` to `CategorizedAchievementList(achievements)`.

## ViewModel

No changes needed. `AchievementUiState.achievements: List<AchievementItem>` remains as-is. The `AchievementItem.def.category` property is used directly in the UI layer for grouping.

## Impact Scope

| File | Change |
|---|---|
| `AchievementDef.kt` | Add `category` field to enum entries |
| New: `AchievementCategory.kt` | Category enum definition |
| `AchievementCard.kt` | Add `CategorySection`, replace `AchievementGrid` with `CategorizedAchievementList` |
| `HomeScreen.kt` | Update `AchievementTabContent` call site |

No database migration. No changes to `AchievementChecker`, `AchievementRepository`, `AchievementDao`, `AchievementEntity`, or `AchievementViewModel`.

## Future Extensibility

Adding a new achievement category requires:
1. Add entry to `AchievementCategory` enum
2. Add new `AchievementDef` entries pointing to the new category

Adding a new achievement to an existing category requires only step 2.
