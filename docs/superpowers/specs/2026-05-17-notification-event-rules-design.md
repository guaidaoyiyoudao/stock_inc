# Design: Notification Event Rules

Date: 2026-05-17
Status: Approved for planning

## Overview

Add a configurable notification event system. The first supported event is a dividend-yield threshold rule: when a stock's current-price dividend yield reaches or exceeds a configured percentage after previously being below it, the app notifies the user.

The system is designed as an extensible rule engine, not a one-off check in the watchlist screen.

## Requirements

1. Users can enable a global dividend-yield threshold notification rule.
2. Users can set the global threshold percentage, with `5%` as the product example and recommended default.
3. Users can enable per-stock override rules with their own threshold percentage.
4. Per-stock rules take precedence over the global rule for the same stock.
5. The rule uses a complete report year of dividend data, based on `DividendEntity.reportDate`.
6. The app checks rules both during foreground quote refresh and through a background periodic worker.
7. The app only sends a notification when the computed yield crosses from below the threshold to at or above the threshold.
8. The app does not repeat notifications while the yield remains above the threshold.
9. If the yield later falls below the threshold, the next upward crossing can notify again.
10. Missing quote data, invalid prices, or missing dividend data must not produce a notification.
11. Android notification permission is requested where required by the platform.

## Non-Goals

- Notification history list.
- Arbitrary user-defined formulas.
- Multiple event types in the first UI.
- Real-time server push.
- Guaranteed exact market-time delivery while the device is asleep or offline.

## Data Model

Create a local Room entity for notification rules.

### NotificationRuleEntity

Fields:

- `id: String`
- `type: String`
  - First value: `DIVIDEND_YIELD_THRESHOLD`
- `stockCode: String?`
  - `null` means global rule.
  - Non-null means per-stock override.
- `enabled: Boolean`
- `thresholdPercent: Double`
- `lastWasAboveThreshold: Boolean?`
  - `null` means no prior comparable check.
- `lastCheckedAt: Long?`
- `lastTriggeredAt: Long?`
- `createdAt: Long`
- `updatedAt: Long`

Indexes:

- Unique index on `(type, stockCode)` so there is only one global threshold rule and one override per stock.

Database migration:

- Add the `notification_rules` table.
- Bump the Room database version by one from the current version.

## Rule Semantics

### Complete Report Year

For each stock, use dividend rows grouped by `reportDate` year.

1. Ignore records whose `reportDate` does not contain a valid four-digit year prefix.
2. Find the latest year that has at least one dividend record.
3. Sum `cashPerShare` for that year.
4. Treat that sum as the complete-year per-share dividend.

This matches the current local data model and avoids introducing external assumptions about fiscal calendars.

### Current Dividend Yield

Formula:

```text
yieldPercent = completeYearCashPerShare / currentPrice * 100
```

No notification is produced when:

- `currentPrice` is missing.
- `currentPrice <= 0`.
- Complete-year dividend data is missing.
- `completeYearCashPerShare <= 0`.
- Threshold is not positive.

### Crossing Logic

For each effective rule and stock:

- `currentAbove = yieldPercent >= thresholdPercent`
- Notify only when `lastWasAboveThreshold == false && currentAbove == true`
- Do not notify when `lastWasAboveThreshold == null`, even if `currentAbove == true`; instead initialize the state. This prevents noisy first-run notifications for already-qualified stocks.
- After each comparable check, persist `lastWasAboveThreshold = currentAbove` and `lastCheckedAt = now`.
- When notification is sent, also persist `lastTriggeredAt = now`.

## Rule Resolution

For a stock:

1. If an enabled per-stock rule exists for `DIVIDEND_YIELD_THRESHOLD`, use it.
2. Else use the enabled global `DIVIDEND_YIELD_THRESHOLD` rule.
3. If neither exists, no check runs for that stock.

Disabled per-stock overrides suppress their own override only. They do not disable the global rule unless the implementation explicitly stores an override mode in a later version. First version keeps the model simple: disabled stock rule is ignored, global can still apply.

## Architecture

### Core Units

- `NotificationRuleDao`
  - Observes and updates rules.
  - Fetches active rules for checks.
- `NotificationRuleRepository`
  - Public API for reading and saving rule settings.
  - Resolves effective rules for a stock set.
- `NotificationRuleEvaluator`
  - Pure Kotlin logic.
  - Calculates complete-year dividend yield.
  - Decides whether a rule crossed upward.
  - Returns evaluation results and state updates.
- `NotificationCheckCoordinator`
  - Loads stocks, dividends, quotes, and rules.
  - Calls evaluator.
  - Persists state updates.
  - Sends notifications through an injected notifier interface.
- `DividendAlertNotifier`
  - Creates notification channel.
  - Checks notification permission.
  - Sends Android system notifications.
- `NotificationCheckWorker`
  - WorkManager worker for background periodic checks.

### Foreground Trigger

After a successful quote refresh in `HomeViewModel`, invoke the coordinator with the same stocks and fresh prices, or expose a repository-level check that can reuse those prices.

Quote failures remain non-blocking. If quotes are unavailable, no notification is sent.

### Background Trigger

Use WorkManager periodic work.

- Enqueue unique periodic work when notifications are enabled or app starts.
- Use network-connected constraint.
- Suggested interval: daily for the first version.
- The worker fetches current quotes for active holdings and evaluates active rules.

The worker is best-effort and subject to Android background execution limits.

## Notification UX

Notification title:

```text
股息率达到目标
```

Notification body:

```text
{股票名} 当前股息率 {yieldPercent}% 已达到 {thresholdPercent}% 阈值
```

Tap behavior:

- Open the app.
- If simple deep link routing is available in the implementation pass, open the stock detail page.
- Otherwise open the main watchlist screen.

## Settings UX

First version:

- Add a notification settings entry from the main app UI.
- Global setting:
  - Enable/disable dividend-yield threshold rule.
  - Edit threshold percent, default `5.0`.
- Per-stock override:
  - Accessible from stock detail or edit holding flow.
  - Enable/disable override.
  - Edit threshold percent.

Validation:

- Threshold must be a positive decimal.
- Save disabled when input is invalid.

Android 13+ permission:

- Ask for `POST_NOTIFICATIONS` before enabling notification delivery.
- If permission is denied, keep rule settings saved but show notifications as disabled/unavailable until permission is granted.

## Error Handling

- Invalid threshold input: show inline validation error.
- Missing quote or dividend data: skip notification and keep user-facing UI stable.
- Notification permission denied: do not crash; keep rules and show permission state.
- Worker failure: retry according to WorkManager defaults; no user-visible error.

## Testing

### Unit Tests

`NotificationRuleEvaluator`:

- Finds the latest valid report year and sums same-year `cashPerShare`.
- Ignores malformed `reportDate`.
- Computes yield percent from complete-year dividend and current price.
- Does not notify on first comparable check when already above threshold.
- Notifies when previous state was below and current state is at threshold.
- Notifies when previous state was below and current state is above threshold.
- Does not notify while remaining above threshold.
- Resets state when current yield falls below threshold.
- Produces no notification for missing price, non-positive price, missing dividends, or non-positive threshold.

Repository/rule resolution:

- Per-stock enabled rule overrides global rule.
- Disabled per-stock rule is ignored and global rule applies.
- No enabled rule means no evaluation.

Coordinator:

- Sends notification when evaluator returns a trigger.
- Persists updated `lastWasAboveThreshold`, `lastCheckedAt`, and `lastTriggeredAt`.
- Does not send notification when permission/notifier reports unavailable.

### Integration Checks

- Foreground quote refresh still updates market value and forecast UI.
- Background worker can be enqueued without duplicate periodic jobs.
- Notification settings save and reload correctly.

## Open Implementation Notes

- Add WorkManager dependencies and Hilt worker integration during planning.
- Use interfaces around time and notification delivery to keep tests deterministic.
- Keep first UI small; notification history can be a separate feature.

## Self-Review

- Placeholder scan: no placeholder markers remain.
- Consistency: all requirements use the same `DIVIDEND_YIELD_THRESHOLD` rule type and report-year calculation.
- Scope: this is one feature: configurable notification event rules with the first event type implemented.
- Ambiguity: first-run behavior is explicit: initialize state without notifying if already above threshold.
