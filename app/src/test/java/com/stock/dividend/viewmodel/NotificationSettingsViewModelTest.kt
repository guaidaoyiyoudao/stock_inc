package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD
import com.stock.dividend.data.local.entity.NotificationRuleEntity
import com.stock.dividend.data.repository.AiAgentConfig
import com.stock.dividend.data.repository.AiAgentConfigRepository
import com.stock.dividend.data.repository.LlmConfigRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class NotificationSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: NotificationRuleRepository = mockk(relaxed = true)
    private val llmConfigRepository: LlmConfigRepository = mockk(relaxed = true)
    private val agentConfigRepository: AiAgentConfigRepository = mockk(relaxed = true)
    private val globalRuleFlow = MutableStateFlow<NotificationRuleEntity?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        globalRuleFlow.value = null
        coEvery { repository.observeGlobalDividendYieldRule() } returns globalRuleFlow
        every { llmConfigRepository.observeConfig() } returns emptyFlow()
        every { agentConfigRepository.observe() } returns MutableStateFlow(AiAgentConfig())
        every { agentConfigRepository.snapshot() } returns AiAgentConfig()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `defaults to disabled five percent when no global rule exists`() = runTest {
        val viewModel = NotificationSettingsViewModel(repository, llmConfigRepository, agentConfigRepository)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.enabled).isFalse()
        assertThat(viewModel.uiState.value.thresholdInput).isEqualTo("5.0")
    }

    @Test
    fun `loads existing global rule`() = runTest {
        globalRuleFlow.value = NotificationRuleEntity(
            id = "global",
            type = NOTIFICATION_RULE_TYPE_DIVIDEND_YIELD_THRESHOLD,
            enabled = true,
            thresholdPercent = 6.5
        )

        val viewModel = NotificationSettingsViewModel(repository, llmConfigRepository, agentConfigRepository)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.enabled).isTrue()
        assertThat(viewModel.uiState.value.thresholdInput).isEqualTo("6.5")
    }

    @Test
    fun `save rejects non positive threshold`() = runTest {
        val viewModel = NotificationSettingsViewModel(repository, llmConfigRepository, agentConfigRepository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.updateThreshold("0")
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.thresholdError).isEqualTo("请输入大于 0 的阈值")
    }

    @Test
    fun `save persists global rule`() = runTest {
        val viewModel = NotificationSettingsViewModel(repository, llmConfigRepository, agentConfigRepository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.updateEnabled(true)
        viewModel.updateThreshold("5.5")
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify {
            repository.saveDividendYieldRule(
                stockCode = null,
                enabled = true,
                thresholdPercent = 5.5,
                now = any()
            )
        }
    }
}
