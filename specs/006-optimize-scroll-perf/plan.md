# Implementation Plan: Optimize Scroll Performance

**Branch**: `006-optimize-scroll-perf` | **Date**: 2026-04-30 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/006-optimize-scroll-perf/spec.md`

## Summary

Optimize the app's scroll performance by eliminating excessive recompositions caused by cascading state emissions in ViewModels, adopting lifecycle-aware state collection, fixing redundant calculations, and reducing per-frame object allocations in composable rendering. The approach targets the root causes of jank: multiple rapid state updates from a single data change, continuous animations on idle screens, and expensive object creation during scroll.

## Technical Context

**Language/Version**: Kotlin 2.0.21
**Primary Dependencies**: Jetpack Compose (BOM 2024.12.01), Material Design 3 (1.3.1), Hilt (2.53.1), Room (2.6.1), Lifecycle (2.8.7), Coroutines (1.9.0)
**Storage**: Room (SQLite), existing DB at version 3
**Testing**: JUnit 4, MockK 1.13.13, Turbine 1.2.0, Coroutines Test 1.9.0
**Target Platform**: Android 7.0+ (minSdk 24, targetSdk 35)
**Project Type**: Mobile app (single Activity + Compose Navigation)
**Performance Goals**: 60fps scroll, <16ms frame time, <100ms touch response
**Constraints**: No visual/behavioral changes, no new dependencies, preserve all existing functionality
**Scale/Scope**: 5 screens, 3 ViewModels, ~40 source files total; optimizations affect 3 screens + 2 ViewModels

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Modern Android Development | PASS | Using Kotlin + Compose + MVVM; optimizing within existing architecture |
| II. Offline-first & Data Persistence | PASS | No changes to data layer or caching behavior |
| III. Data Accuracy | PASS | No data transformations; only UI rendering optimization |
| IV. Simplicity & Maintainability | PASS | Simplifying ViewModel logic (fewer coroutines, less redundancy) |
| V. User-friendly Error Handling | PASS | No error handling changes |

**Gate Result**: PASS — all principles satisfied. No violations to justify.

## Project Structure

### Documentation (this feature)

```text
specs/006-optimize-scroll-perf/
├── plan.md              # This file
├── research.md          # Phase 0: performance analysis findings
├── data-model.md        # Phase 1: state flow architecture
├── quickstart.md        # Phase 1: verification guide
├── checklists/          # Quality checklists
└── tasks.md             # Phase 2 (via /speckit.tasks)
```

### Source Code (repository root)

```text
app/src/main/java/com/stock/dividend/
├── viewmodel/
│   ├── HomeViewModel.kt         # MODIFY: consolidate state emissions
│   └── StockDetailViewModel.kt  # MODIFY: merge dual observers
├── ui/
│   ├── screen/
│   │   ├── HomeScreen.kt           # MODIFY: collectAsStateWithLifecycle
│   │   ├── StockDetailScreen.kt    # MODIFY: lifecycle + fix animation + add key
│   │   ├── AddStockScreen.kt       # MODIFY: lifecycle + add key
│   │   ├── EditHoldingScreen.kt    # MODIFY: lifecycle
│   │   └── FireGoalSetupScreen.kt  # MODIFY: lifecycle
│   └── component/
│       └── StockCard.kt            # MODIFY: cache formatters
├── data/
│   └── local/entity/
│       ├── StockEntity.kt          # MODIFY: add @Stable annotation
│       └── DividendEntity.kt       # MODIFY: add @Stable annotation
└── MainActivity.kt                 # No changes

app/src/test/java/com/stock/dividend/
└── viewmodel/
    ├── HomeViewModelTest.kt        # UPDATE: adapt to new state flow
    └── StockDetailViewModelTest.kt # UPDATE: adapt to new state flow
```

**Structure Decision**: Single Android app module. All changes are modifications to existing files — no new files needed.

## Complexity Tracking

No violations to justify. Constitution check passed cleanly.
