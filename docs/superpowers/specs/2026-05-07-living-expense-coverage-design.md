# Living Expense Coverage Design

Date: 2026-05-07

## Goal

Improve the existing expense coverage page so users can define concrete living expenses and see how their forecast annual dividend income covers those expenses one by one.

The feature answers: "Which parts of my life are currently supported by dividend income?"

## Scope

In scope:

- Add persistent living expense items.
- Support monthly and yearly expense amounts.
- Default coverage order to item creation order.
- Allow users to manually change coverage order.
- Use forecast annual dividend income as the primary coverage source.
- Show complete, partial, and uncovered states for each expense item.
- Replace the current single-goal coverage page content with the expense queue experience.

Out of scope:

- Drag-and-drop sorting for the first implementation.
- Multiple currencies.
- Per-month dividend cash flow timing.
- Sharing or exporting the coverage plan.
- Removing the existing FIRE goal model.

## Data Model

Add a Room entity named `LivingExpenseItemEntity` backed by `living_expense_items`.

Fields:

- `id: Long`: auto-generated primary key.
- `name: String`: user-facing expense name, such as rent, food, or transport.
- `amount: Double`: positive amount entered by the user.
- `period: String`: `MONTHLY` or `YEARLY`.
- `sortOrder: Int`: lower values are covered first.
- `createdAt: Long`: creation timestamp.
- `updatedAt: Long`: last update timestamp.

Annualized amount rules:

- `MONTHLY`: `amount * 12`.
- `YEARLY`: `amount`.

The default `sortOrder` is the next value after the current maximum order. This makes the default coverage sequence match the add sequence.

## Repository And DAO

Add `LivingExpenseItemDao` with:

- Observe all items ordered by `sortOrder`, then `createdAt`.
- Insert an item.
- Update an item.
- Delete an item.
- Get the current max `sortOrder`.
- Update item order for manual reordering.

Add `LivingExpenseRepository` to keep DAO access out of the ViewModel and to own small write workflows, such as assigning the next order on insert and swapping neighboring orders for up/down actions.

## Coverage Algorithm

The coverage source is `DividendIncomeRepository.observeForecastTotal()`.

For each living expense item ordered by `sortOrder`:

1. Convert the item to an annualized amount.
2. Allocate remaining forecast dividend income to that item.
3. Mark the item as:
   - `COVERED` when allocated amount is greater than or equal to annualized amount.
   - `PARTIAL` when allocated amount is greater than zero but less than annualized amount.
   - `UNCOVERED` when allocated amount is zero.
4. Decrease remaining income by the allocated amount.

The ViewModel exposes:

- Forecast annual dividend income.
- Total annualized living expenses.
- Overall coverage ratio.
- Covered item count.
- Current item being partially covered, if any.
- Remaining dividend income after all covered expenses, if positive.
- Per-item coverage rows.

## UI

Reuse the existing `ExpenseCoverageScreen` route and entry point.

Page title: `生活支出覆盖`.

Top summary card:

- Overall coverage percentage.
- Forecast annual dividend income.
- Total annualized living expenses.
- Covered item count.
- Current covering item or completion message.

Expense coverage list:

- Item name.
- Original amount with period, for example `¥3,000.00 / 月`.
- Annualized amount.
- Coverage status.
- Covered amount and remaining gap.
- Up/down controls for manual ordering.
- Edit and delete controls.

Empty state:

- Explain that the user can add rent, food, transport, and other living expenses.
- Provide a primary action to add the first expense item.

Add/edit dialog:

- Name input.
- Amount input.
- Period selector with monthly and yearly options.
- Validation messages for blank name, invalid amount, and non-positive amount.

Sorting:

- Initial implementation uses up/down actions instead of drag-and-drop.
- Up is disabled for the first item.
- Down is disabled for the last item.

## Existing FIRE Goal Compatibility

The existing `fire_goal` table remains in place.

The improved expense coverage page no longer uses `fire_goal.targetAmount` as its main target. Its target is the sum of annualized living expense items.

The watchlist FIRE card can continue to use the existing FIRE goal during the first implementation. A follow-up can make that card read the living expense total so the entry card and detail page share the same target.

## Error Handling

Input validation:

- Name must not be blank.
- Amount must parse as a number.
- Amount must be greater than zero.
- Amount must stay within a practical upper bound.

Data states:

- No expense items: show empty state and zero target.
- No forecast dividend income: show all items as uncovered.
- Forecast income exceeds all expenses: show all items covered and surface remaining surplus.

## Testing

ViewModel and algorithm tests:

- Monthly items annualize by multiplying by 12.
- Yearly items keep their entered amount.
- Items are covered in `sortOrder`.
- Partial coverage is assigned to the first item that cannot be fully covered.
- Items after a partial item are uncovered.
- No expense items returns an empty list and zero target.
- No forecast income marks all items uncovered.
- Reordering changes the coverage allocation.

Repository/DAO tests:

- Insert assigns the next sort order.
- Observe returns items in coverage order.
- Update changes name, amount, and period.
- Delete removes the item.
- Move up/down swaps order with the neighboring item.

UI-focused tests:

- Empty state is shown when there are no expense items.
- Summary card displays forecast income and total living expenses.
- Covered, partial, and uncovered labels appear for representative rows.

## Acceptance Criteria

- A user can add multiple living expense items with monthly or yearly periods.
- Added items are covered in the same order they were created.
- A user can manually move items up or down to change the coverage order.
- The page uses forecast annual dividend income as the primary progress source.
- Each item clearly shows whether it is fully covered, partially covered, or uncovered.
- The summary communicates total coverage across all living expenses.
