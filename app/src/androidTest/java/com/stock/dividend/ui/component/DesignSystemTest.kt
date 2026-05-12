package com.stock.dividend.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stock.dividend.ui.theme.StockDividendTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DesignSystemTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sectionHeaderShowsTitleAndRunsAction() {
        var clicked = false

        composeRule.setContent {
            StockDividendTheme {
                SectionHeader(
                    title = "收入记录",
                    actionText = "添加收入",
                    actionIcon = Icons.Default.Add,
                    onActionClick = { clicked = true }
                )
            }
        }

        composeRule.onNodeWithText("收入记录").assertIsDisplayed()
        composeRule.onNodeWithText("添加收入").assertIsDisplayed()
        composeRule.onNodeWithText("添加收入").performClick()

        assertTrue(clicked)
    }
}
