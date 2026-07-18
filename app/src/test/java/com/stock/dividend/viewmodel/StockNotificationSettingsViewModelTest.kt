package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_PRICE_ABOVE
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.repository.NotificationRuleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class StockNotificationSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: NotificationRuleRepository = mockk(relaxed = true)
    private val stockRulesFlow = MutableStateFlow<List<NotificationRuleEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        stockRulesFlow.value = emptyList()
        coEvery { repository.observeStockRules("sz.000001") } returns stockRulesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads all supported custom rule settings`() = runTest {
        stockRulesFlow.value = listOf(
            rule(NOTIFICATION_RULE_TYPE_PRICE_ABOVE, enabled = true, threshold = 12.5),
            rule(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD, enabled = true, threshold = 3.0)
        )

        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.rules.map { it.type }).containsExactlyElementsIn(
            stockCustomNotificationRuleTypes
        ).inOrder()
        assertThat(viewModel.uiState.value.rule(NOTIFICATION_RULE_TYPE_PRICE_ABOVE).enabled).isTrue()
        assertThat(viewModel.uiState.value.rule(NOTIFICATION_RULE_TYPE_PRICE_ABOVE).thresholdInput).isEqualTo("12.5")
        assertThat(viewModel.uiState.value.rule(NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_BELOW_THRESHOLD).enabled).isTrue()
    }

    @Test
    fun `save persists every custom rule`() = runTest {
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.updateEnabled(NOTIFICATION_RULE_TYPE_PRICE_ABOVE, true)
        viewModel.updateThreshold(NOTIFICATION_RULE_TYPE_PRICE_ABOVE, "12.5")
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify {
            repository.saveRule(
                type = NOTIFICATION_RULE_TYPE_PRICE_ABOVE,
                stockCode = "sz.000001",
                enabled = true,
                thresholdValue = 12.5,
                now = any()
            )
        }
    }

    private fun viewModel() = StockNotificationSettingsViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(mapOf("code" to "sz.000001"))
    )

    private fun StockCustomNotificationSettingsUiState.rule(type: String) =
        rules.first { it.type == type }

    private fun rule(
        type: String,
        enabled: Boolean,
        threshold: Double
    ) = NotificationRuleEntity(
        id = type,
        type = type,
        stockCode = "sz.000001",
        enabled = enabled,
        thresholdPercent = threshold,
        createdAt = 0L,
        updatedAt = 0L
    )
}
