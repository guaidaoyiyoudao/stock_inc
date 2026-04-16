# UI Contracts: FIRE Retirement Goal Progress

**Branch**: `004-fire-retirement-goal` | **Date**: 2026-04-16

## Screen Contracts

### FireProgressCard (Component on HomeScreen)

**Purpose**: Display FIRE progress bar and key figures on the home page.

**States**:

| State | Display |
|-------|---------|
| No goal set | Card with "设置 FIRE 目标" prompt text, tap to navigate to setup |
| Goal set, progress < 100% | Linear progress bar + "¥{forecast} / ¥{target}" + "{percentage}%" |
| Goal set, progress >= 100% | Full progress bar (accent/gold color) + "🎉 已达标!" badge + amounts |
| Goal set, forecast = 0 | Progress bar at 0% + "¥0 / ¥{target}" |

**Interaction**: Tap entire card → navigate to `FireGoalSetupScreen`

**Data input**: `targetAmount: Double?`, `forecastTotal: Double`

---

### FireGoalSetupScreen (New Screen)

**Purpose**: Set, modify, or delete the FIRE target amount.

**Input fields**:

| Field | Type | Validation | Placeholder |
|-------|------|------------|-------------|
| 目标年支出 | OutlinedTextField (number) | > 0, <= 999B, non-empty | "例如：200000" |

**Actions**:

| Action | Button | Condition |
|--------|--------|-----------|
| Save new goal | "确认" FAB or button | Input valid, no existing goal |
| Update goal | "确认" FAB or button | Input valid, goal already exists |
| Delete goal | "删除目标" text button | Goal exists, shown in red |
| Cancel | Back navigation | Always |

**Delete confirmation**: AlertDialog with "确认删除 FIRE 目标？" message, "取消" / "删除" buttons.

**Data input from navigation**: None (single goal, no args needed).

**Data output**: Goal saved/updated/deleted in Room DB, observed reactively by HomeViewModel.

---

## Navigation Contract

### New Route

```
FIRE_GOAL_SETUP = "fireGoalSetup"
```

### Navigation Flows

```
HomeScreen (FireProgressCard tap) → FireGoalSetupScreen
FireGoalSetupScreen (back/save/delete) → HomeScreen (popBackStack)
```

No arguments passed — the screen loads current goal state from DB via ViewModel.
