# stock_inc Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-04-30

## Active Technologies
- Kotlin 2.0 + Jetpack Compose, Material Design 3, Hilt, Room, Retrofit + OkHttp, Navigation Compose, Coroutines + Flow (001-stock-dividend-tracker)
- Room (Android 本地 SQLite) — 数据库版本从 v1 升级到 v2 (001-stock-dividend-tracker)
- Kotlin 2.0 + Jetpack Compose, Material Design 3, Hilt, Room, Coroutines + Flow (003-dividend-lazy-load)
- Room (SQLite)，已有 `dividends` 表，无需修改 schema (003-dividend-lazy-load)
- Kotlin 2.0.21 + Jetpack Compose (BOM 2024.12.01), Material Design 3 (1.3.1), Hilt (2.53.1), Room (2.6.1), Navigation Compose (2.8.5), Coroutines (1.9.0) (004-fire-retirement-goal)
- Room (SQLite), existing DB at version 2, will migrate to v3 (004-fire-retirement-goal)
- Kotlin 2.0.21 + Jetpack Compose (BOM 2024.12.01), Material Design 3 (1.3.1), Hilt (2.53.1), Room (2.6.1), Lifecycle (2.8.7), Coroutines (1.9.0) (006-optimize-scroll-perf)
- Room (SQLite), existing DB at version 3 (006-optimize-scroll-perf)

- Kotlin 2.0 + Jetpack Compose, Material Design 3, Hilt, Room, Retrofit + OkHttp, (001-stock-dividend-tracker)

## Project Structure

```text
src/
tests/
```

## Commands

# Add commands for Kotlin 2.0

## Code Style

Kotlin 2.0: Follow standard conventions

## Recent Changes
- 006-optimize-scroll-perf: Added Kotlin 2.0.21 + Jetpack Compose (BOM 2024.12.01), Material Design 3 (1.3.1), Hilt (2.53.1), Room (2.6.1), Lifecycle (2.8.7), Coroutines (1.9.0)
- 004-fire-retirement-goal: Added Kotlin 2.0.21 + Jetpack Compose (BOM 2024.12.01), Material Design 3 (1.3.1), Hilt (2.53.1), Room (2.6.1), Navigation Compose (2.8.5), Coroutines (1.9.0)
- 003-dividend-lazy-load: Added Kotlin 2.0 + Jetpack Compose, Material Design 3, Hilt, Room, Coroutines + Flow


<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
