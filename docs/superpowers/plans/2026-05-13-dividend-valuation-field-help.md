# Dividend Valuation Field Help Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add clickable field explanations to the editable assumptions on the dividend discount valuation page.

**Architecture:** Keep help text as a small Kotlin data model in `DividendValuationScreen.kt`, because the interaction is local UI state and does not affect valuation logic. Add a JVM unit test that verifies all six required help entries exist with the approved Chinese copy. Render each input label with an info icon that opens a Material 3 `AlertDialog`.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose Material 3, Material Icons, JUnit, Truth.

---

### Task 1: Field Help Entries and Dialog UI

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/DividendValuationScreen.kt`
- Create: `app/src/test/java/com/stock/dividend/ui/screen/DividendValuationFieldHelpTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/stock/dividend/ui/screen/DividendValuationFieldHelpTest.kt`:

```kotlin
package com.stock.dividend.ui.screen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DividendValuationFieldHelpTest {
    @Test
    fun `field help contains explanations for every editable assumption`() {
        val helpByTitle = dividendValuationFieldHelp.associateBy { it.title }

        assertThat(helpByTitle.keys).containsExactly(
            "股息基准",
            "未来股息增长率",
            "折现率",
            "终值增长率",
            "预测年限",
            "安全边际"
        )
        assertThat(helpByTitle["股息基准"]!!.description)
            .isEqualTo("估值的起点股息，默认取最近 5 个可用分红年份的每股现金分红平均值；没有历史分红时可手动输入。")
        assertThat(helpByTitle["折现率"]!!.description)
            .isEqualTo("把未来现金流折算成今天价值时使用的回报率要求，越高则估值越低。")
        assertThat(helpByTitle["终值增长率"]!!.description)
            .isEqualTo("预测期结束后，假设股息长期稳定增长的比例；必须低于折现率。")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.ui.screen.DividendValuationFieldHelpTest"
```

Expected: FAIL because `dividendValuationFieldHelp` does not exist.

- [ ] **Step 3: Implement help entries and dialog**

In `DividendValuationScreen.kt`, add:

```kotlin
data class DividendValuationFieldHelp(
    val title: String,
    val description: String
)

val dividendValuationFieldHelp = listOf(
    DividendValuationFieldHelp("股息基准", "估值的起点股息，默认取最近 5 个可用分红年份的每股现金分红平均值；没有历史分红时可手动输入。"),
    DividendValuationFieldHelp("未来股息增长率", "假设未来每年股息增长的比例。"),
    DividendValuationFieldHelp("折现率", "把未来现金流折算成今天价值时使用的回报率要求，越高则估值越低。"),
    DividendValuationFieldHelp("终值增长率", "预测期结束后，假设股息长期稳定增长的比例；必须低于折现率。"),
    DividendValuationFieldHelp("预测年限", "逐年预测股息现金流的年数。"),
    DividendValuationFieldHelp("安全边际", "在内在价值基础上打折得到更保守的买入价。")
)
```

Update `AssumptionCard` to keep `selectedHelp` in `remember` state, pass the matching help entry into each `AssumptionField`, and render `AlertDialog` when selected. Update `AssumptionField` so the label is a row containing label text plus an `IconButton` with `Icons.Filled.Info`.

- [ ] **Step 4: Run tests and compile**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.ui.screen.DividendValuationFieldHelpTest"
./gradlew compileDebugKotlin
```

Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/DividendValuationScreen.kt app/src/test/java/com/stock/dividend/ui/screen/DividendValuationFieldHelpTest.kt docs/superpowers/plans/2026-05-13-dividend-valuation-field-help.md
git commit -m "feat: add dividend valuation field help"
```

---

## Self-Review

- Spec coverage: all six editable assumption fields have help copy and click behavior.
- Placeholder scan: no placeholder markers or open-ended implementation steps remain.
- Type consistency: the test references the same `DividendValuationFieldHelp` and `dividendValuationFieldHelp` names used by the UI.
