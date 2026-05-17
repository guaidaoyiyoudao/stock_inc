# Notification Event Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an extensible notification rule system with a dividend-yield threshold rule checked during foreground quote refresh and background periodic work.

**Architecture:** Add a Room-backed rule model, pure evaluator, coordinator, Android notifier, WorkManager worker, and small Compose settings UI. `HomeViewModel` will call the coordinator after quote refresh, while background work uses the same coordinator and repository APIs.

**Tech Stack:** Kotlin 2.0.21, Java 17, Room, Hilt, WorkManager, Jetpack Compose, Android notifications, JUnit/Truth/MockK/coroutines-test.

---

## File Structure

- `data/local/entity/NotificationRuleEntity.kt`: Room entity for global and per-stock rules.
- `data/local/dao/NotificationRuleDao.kt`: CRUD and rule lookup queries.
- `data/local/AppDatabase.kt`, `di/DatabaseModule.kt`: schema version bump and DAO provider.
- `data/repository/NotificationRuleRepository.kt`: settings API and rule resolution.
- `data/notification/NotificationRuleEvaluator.kt`: pure dividend-yield and threshold-crossing logic.
- `data/notification/NotificationCheckCoordinator.kt`: loads data, evaluates rules, persists state, sends alerts.
- `data/notification/DividendAlertNotifier.kt`: Android notification channel and notification sending.
- `data/notification/NotificationCheckWorker.kt`: WorkManager periodic worker.
- `data/notification/NotificationScheduler.kt`: unique periodic work setup.
- `StockDividendApp.kt`: initialize background work.
- `HomeViewModel.kt`: invoke foreground notification checks after quote refresh.
- `ui/screen/NotificationSettingsScreen.kt`: global notification rule UI.
- `ui/screen/StockNotificationSettingsScreen.kt`: per-stock override UI.
- `ui/screen/MainScaffold.kt`, `ui/navigation/AppNavigation.kt`, `StockDetailScreen.kt`: navigation entry points.
- Unit tests for evaluator, repository/coordinator, and ViewModels.

## Tasks

1. Add notification rule entity, DAO, database migration, and repository with tests.
2. Add pure `NotificationRuleEvaluator` with tests for complete report year, yield math, and crossing behavior.
3. Add coordinator and notifier abstraction with tests for trigger persistence and notification sending.
4. Add WorkManager dependencies, worker, scheduler, manifest permission, and app startup scheduling.
5. Add global notification settings UI and ViewModel.
6. Add per-stock override UI entry and ViewModel.
7. Wire foreground quote refresh to the coordinator.
8. Run `./gradlew testDebugUnitTest` and commit each green slice.

## TDD Commands

Use targeted tests while building:

```bash
./gradlew testDebugUnitTest --tests com.stock.dividend.data.notification.NotificationRuleEvaluatorTest
./gradlew testDebugUnitTest --tests com.stock.dividend.data.repository.NotificationRuleRepositoryTest
./gradlew testDebugUnitTest --tests com.stock.dividend.data.notification.NotificationCheckCoordinatorTest
./gradlew testDebugUnitTest --tests com.stock.dividend.viewmodel.NotificationSettingsViewModelTest
./gradlew testDebugUnitTest
```

## Self-Review

- Spec coverage: data model, complete report year calculation, crossing-only notification, foreground checks, background checks, global rule, per-stock override, and permission-aware notification delivery are all mapped to tasks.
- Placeholder scan: no unresolved placeholders.
- Type consistency: rule type is represented by the string `DIVIDEND_YIELD_THRESHOLD`; threshold values are `Double` percentages; stock override uses nullable `stockCode`.
