# Implementation Plan: FIRE Retirement Goal Progress

**Branch**: `004-fire-retirement-goal` | **Date**: 2026-04-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/004-fire-retirement-goal/spec.md`

## Summary

Add FIRE (Financial Independence, Retire Early) retirement goal tracking: users set a target annual income amount, and the home page displays a progress bar showing expected annual dividend income (from existing forecast) as a percentage of the target. Includes set/modify/delete target flows and a new FIRE progress card on the home screen.

## Technical Context

**Language/Version**: Kotlin 2.0.21
**Primary Dependencies**: Jetpack Compose (BOM 2024.12.01), Material Design 3 (1.3.1), Hilt (2.53.1), Room (2.6.1), Navigation Compose (2.8.5), Coroutines (1.9.0)
**Storage**: Room (SQLite), existing DB at version 2, will migrate to v3
**Testing**: JUnit 4, MockK 1.13.13, Turbine 1.2.0, Truth 1.4.4, Compose UI Test
**Target Platform**: Android 7.0+ (minSdk 24), targetSdk 35
**Project Type**: Mobile app (single Activity + Composable Screens)
**Performance Goals**: FIRE card renders within 1s on home page load
**Constraints**: Offline-first, single-user, no network required for FIRE feature
**Scale/Scope**: ~10 screens total, single user, local-only data

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Modern Android Development | PASS | Kotlin + Compose + Material 3 + MVVM + Hilt |
| II. Offline-first & Persistence | PASS | FIRE goal stored in Room DB, no network needed |
| III. Data Accuracy | PASS | No external data; target amount is user input, progress based on existing forecast |
| IV. Simplicity & Maintainability | PASS | Single entity, single DAO, minimal new files, reuses existing forecastTotal |
| V. User-friendly Error Handling | PASS | Validation on input, no network errors possible |
| Design Standards | PASS | Material 3, Chinese UI, Card component, dark mode support |
| Development Workflow | PASS | Gradle Kotlin DSL, Version Catalog, Room, Coroutines+Flow |

**Gate Result**: PASS — no violations.

## Project Structure

### Documentation (this feature)

```text
specs/004-fire-retirement-goal/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
app/src/main/java/com/stock/dividend/
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt          # MODIFY: add entity, bump version to 3, add migration
│   │   ├── entity/
│   │   │   └── FireGoalEntity.kt   # NEW: FIRE target amount entity
│   │   └── dao/
│   │       └── FireGoalDao.kt      # NEW: CRUD for FireGoal
│   └── repository/
│       └── FireGoalRepository.kt   # NEW: Repository for FireGoal
├── di/
│   └── DatabaseModule.kt           # MODIFY: provide FireGoalDao
├── ui/
│   ├── navigation/
│   │   └── AppNavigation.kt        # MODIFY: add FIRE_GOAL_SETUP route
│   ├── screen/
│   │   └── FireGoalSetupScreen.kt  # NEW: set/modify/delete target screen
│   └── component/
│       └── FireProgressCard.kt     # NEW: progress bar card for home page
└── viewmodel/
    ├── HomeViewModel.kt            # MODIFY: add FIRE state, observe FireGoal
    └── FireGoalViewModel.kt        # NEW: manage target CRUD
```

**Structure Decision**: Follows existing pattern — new entity/DAO/repository under `data/`, new ViewModel under `viewmodel/`, new screens under `ui/screen/`, new component under `ui/component/`.

## Complexity Tracking

No violations to justify.
