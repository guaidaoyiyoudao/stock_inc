package com.stock.dividend.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stock.dividend.ui.theme.StockDividendTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class IncomeTimelineCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun manualIncomeShowsClearSourceAndKeepsMenuActions() {
        var edited = false
        var deleted = false

        composeRule.setContent {
            StockDividendTheme {
                IncomeTimelineCard(
                    date = "2026-05-12",
                    stockName = "招商银行",
                    amount = 128.50,
                    source = "manual",
                    note = "手动补录",
                    onEdit = { edited = true },
                    onDelete = { deleted = true }
                )
            }
        }

        composeRule.onNodeWithText("实际到账").assertIsDisplayed()
        composeRule.onNodeWithText("¥128.50").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("收入记录操作").performClick()
        composeRule.onNodeWithText("编辑").performClick()
        assertTrue(edited)

        composeRule.onNodeWithContentDescription("收入记录操作").performClick()
        composeRule.onNodeWithText("删除").performClick()
        assertTrue(deleted)
    }

    @Test
    fun estimatedIncomeKeepsCorrectionActionInMenu() {
        var corrected = false

        composeRule.setContent {
            StockDividendTheme {
                IncomeTimelineCard(
                    date = "2026-05-12",
                    stockName = "中国平安",
                    amount = 88.00,
                    source = "forecast",
                    exDividendDate = "2026-05-10",
                    onCorrect = { corrected = true }
                )
            }
        }

        composeRule.onNodeWithText("推算待确认").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("收入记录操作").performClick()
        composeRule.onNodeWithText("修正金额").performClick()
        assertTrue(corrected)
    }
}
