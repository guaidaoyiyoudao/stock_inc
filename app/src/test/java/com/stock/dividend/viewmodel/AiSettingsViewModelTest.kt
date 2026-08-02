package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.AiAgentConfig
import com.stock.dividend.data.repository.AiAgentConfigRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun save_persistsAllFields() = runTest {
        val repository = mockk<AiAgentConfigRepository>(relaxed = true)
        val configFlow = MutableStateFlow(AiAgentConfig())
        every { repository.observe() } returns configFlow
        val vm = AiSettingsViewModel(repository)
        advanceUntilIdle()

        vm.onSystemPromptChanged("回答加 emoji")
        vm.onTemperatureChanged("0.7")
        vm.onMaxTokensChanged("2048")
        vm.save()
        advanceUntilIdle()

        coVerify {
            repository.saveConfig(AiAgentConfig(
                systemPrompt = "回答加 emoji",
                temperature = 0.7f,
                maxTokens = 2048,
            ))
        }
        assertThat(vm.uiState.value.saved).isTrue()
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun blankTemperatureAndMaxTokens_savedAsNull() = runTest {
        val repository = mockk<AiAgentConfigRepository>(relaxed = true)
        val configFlow = MutableStateFlow(AiAgentConfig())
        every { repository.observe() } returns configFlow
        val vm = AiSettingsViewModel(repository)
        advanceUntilIdle()

        vm.onSystemPromptChanged("只用默认行为")
        vm.save()
        advanceUntilIdle()

        // 空 → null（用模型默认），而非 0
        coVerify {
            repository.saveConfig(AiAgentConfig(
                systemPrompt = "只用默认行为",
                temperature = null,
                maxTokens = null,
            ))
        }
    }

    @Test
    fun outOfRangeTemperature_setsErrorAndDoesNotSave() = runTest {
        val repository = mockk<AiAgentConfigRepository>(relaxed = true)
        val configFlow = MutableStateFlow(AiAgentConfig())
        every { repository.observe() } returns configFlow
        val vm = AiSettingsViewModel(repository)
        advanceUntilIdle()

        vm.onTemperatureChanged("3.5")
        vm.save()
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).isEqualTo("温度需在 0~2 之间")
        assertThat(vm.uiState.value.saved).isFalse()
        coVerify(exactly = 0) { repository.saveConfig(any()) }
    }

    @Test
    fun nonPositiveMaxTokens_setsErrorAndDoesNotSave() = runTest {
        val repository = mockk<AiAgentConfigRepository>(relaxed = true)
        val configFlow = MutableStateFlow(AiAgentConfig())
        every { repository.observe() } returns configFlow
        val vm = AiSettingsViewModel(repository)
        advanceUntilIdle()

        vm.onMaxTokensChanged("0")
        vm.save()
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).isEqualTo("最大输出长度需为大于 0 的整数")
        assertThat(vm.uiState.value.saved).isFalse()
        coVerify(exactly = 0) { repository.saveConfig(any()) }
    }

    @Test
    fun restoreDefaultPrompt_clearsPromptDraft() = runTest {
        val repository = mockk<AiAgentConfigRepository>(relaxed = true)
        val configFlow = MutableStateFlow(AiAgentConfig(systemPrompt = "旧指令"))
        every { repository.observe() } returns configFlow
        val vm = AiSettingsViewModel(repository)
        advanceUntilIdle()
        assertThat(vm.uiState.value.systemPromptInput).isEqualTo("旧指令")

        vm.restoreDefaultPrompt()
        assertThat(vm.uiState.value.systemPromptInput).isEmpty()
    }

    @Test
    fun parseTemperature_validatesRange() {
        assertThat(AiSettingsViewModel.parseTemperature("0")).isEqualTo(0f)
        assertThat(AiSettingsViewModel.parseTemperature("1.5")).isEqualTo(1.5f)
        assertThat(AiSettingsViewModel.parseTemperature("2")).isEqualTo(2f)
        assertThat(AiSettingsViewModel.parseTemperature("2.1")).isEqualTo(AiSettingsViewModel.INVALID_TEMP)
        assertThat(AiSettingsViewModel.parseTemperature("abc")).isEqualTo(AiSettingsViewModel.INVALID_TEMP)
    }

    @Test
    fun parseMaxTokens_validatesPositive() {
        assertThat(AiSettingsViewModel.parseMaxTokens("1")).isEqualTo(1)
        assertThat(AiSettingsViewModel.parseMaxTokens("4096")).isEqualTo(4096)
        assertThat(AiSettingsViewModel.parseMaxTokens("0")).isEqualTo(AiSettingsViewModel.INVALID_MAX_TOKENS)
        assertThat(AiSettingsViewModel.parseMaxTokens("-5")).isEqualTo(AiSettingsViewModel.INVALID_MAX_TOKENS)
    }
}
