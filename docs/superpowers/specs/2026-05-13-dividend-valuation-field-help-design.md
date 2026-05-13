# Dividend Valuation Field Help Design

## Summary

Add inline explanation affordances to the editable assumptions on the dividend discount valuation page. Each assumption field gets an info icon that opens a short dialog explaining the field meaning and how it affects valuation.

## Scope

Only the editable fields inside the `估值假设` card are in scope:

- 股息基准
- 未来股息增长率
- 折现率
- 终值增长率
- 预测年限
- 安全边际

Result fields, cash flow rows, presets, and navigation are out of scope for this change.

## User Interaction

Each assumption row displays its normal input field plus a small info icon near the field label. Tapping the icon opens an `AlertDialog` with:

- Dialog title: the field name
- Dialog body: one concise explanation
- Confirm action: `知道了`

Only one help dialog is visible at a time. Dismissing the dialog returns the user to the valuation form without changing any input value.

## Field Copy

- 股息基准：估值的起点股息，默认取最近 5 个可用分红年份的每股现金分红平均值；没有历史分红时可手动输入。
- 未来股息增长率：假设未来每年股息增长的比例。
- 折现率：把未来现金流折算成今天价值时使用的回报率要求，越高则估值越低。
- 终值增长率：预测期结束后，假设股息长期稳定增长的比例；必须低于折现率。
- 预测年限：逐年预测股息现金流的年数。
- 安全边际：在内在价值基础上打折得到更保守的买入价。

## Implementation Notes

Implement the help UI inside `DividendValuationScreen.kt`. Keep this as Compose-local UI state because the help dialog does not affect valuation state, persistence, or business logic. Do not modify `DividendValuationViewModel`, calculation formulas, or repository code.

Use Material 3 components already available in the app:

- `IconButton` for the info affordance
- `Icons.Filled.Info` if available in the current material icons dependency
- `AlertDialog` for the explanation dialog

If `Icons.Filled.Info` is unavailable, use a text `?` affordance inside an `IconButton` to keep the interaction consistent without adding dependencies.

## Testing

Run `./gradlew compileDebugKotlin` to verify the Compose code compiles. No ViewModel or calculator tests are required because no business behavior changes.
