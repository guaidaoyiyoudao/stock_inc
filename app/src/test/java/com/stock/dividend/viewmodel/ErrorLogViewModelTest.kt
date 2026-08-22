package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.ErrorLogEntity
import com.stock.dividend.data.repository.ErrorLogRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
class ErrorLogViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val repository = mockk<ErrorLogRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.clearAll() } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun logEntity(id: Long, category: String = "NETWORK") = ErrorLogEntity(
        id = id, timestamp = 1_700_000_000_000, category = category,
        source = "行情", message = "行情获取失败（2 只标的）",
        detail = "java.io.IOException: timeout",
    )

    @Test
    fun `init collects logs and maps category label`() = runTest {
        every { repository.observeAll() } returns MutableStateFlow(
            listOf(logEntity(1), logEntity(2, category = "LLM"))
        )

        val vm = ErrorLogViewModel(repository)
        advanceUntilIdle()

        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.logs).hasSize(2)
        assertThat(vm.uiState.value.logs[0].categoryLabel).isEqualTo("数据获取")
        assertThat(vm.uiState.value.logs[1].categoryLabel).isEqualTo("AI 调用")
    }

    @Test
    fun `unknown category raw falls back to raw text`() = runTest {
        every { repository.observeAll() } returns MutableStateFlow(
            listOf(logEntity(1, category = "WHATEVER"))
        )

        val vm = ErrorLogViewModel(repository)
        advanceUntilIdle()

        assertThat(vm.uiState.value.logs[0].categoryLabel).isEqualTo("WHATEVER")
    }

    @Test
    fun `collect failure resets loading state`() = runTest {
        every { repository.observeAll() } throws IllegalStateException("db down")

        val vm = ErrorLogViewModel(repository)
        advanceUntilIdle()

        // 红线 #3：collect 异常退出也不能停在加载态
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `toggleExpanded toggles single expansion`() = runTest {
        every { repository.observeAll() } returns MutableStateFlow(
            listOf(logEntity(1), logEntity(2))
        )
        val vm = ErrorLogViewModel(repository)
        advanceUntilIdle()

        vm.toggleExpanded(1)
        assertThat(vm.uiState.value.expandedLogId).isEqualTo(1)

        // 换一条：仍单展开
        vm.toggleExpanded(2)
        assertThat(vm.uiState.value.expandedLogId).isEqualTo(2)

        // 再点同一条：收起
        vm.toggleExpanded(2)
        assertThat(vm.uiState.value.expandedLogId).isNull()
    }

    @Test
    fun `confirm clear clears repository and shows message`() = runTest {
        every { repository.observeAll() } returns MutableStateFlow(listOf(logEntity(1)))

        val vm = ErrorLogViewModel(repository)
        advanceUntilIdle()

        vm.onClearClicked()
        assertThat(vm.uiState.value.confirmingClear).isTrue()

        vm.confirmClear()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.clearAll() }
        assertThat(vm.uiState.value.isClearing).isFalse()
        assertThat(vm.uiState.value.confirmingClear).isFalse()
        assertThat(vm.uiState.value.message).isEqualTo("已清理全部失败日志")

        vm.consumeMessage()
        assertThat(vm.uiState.value.message).isNull()
    }

    @Test
    fun `dismiss confirm cancels clearing`() = runTest {
        every { repository.observeAll() } returns MutableStateFlow(listOf(logEntity(1)))

        val vm = ErrorLogViewModel(repository)
        advanceUntilIdle()

        vm.onClearClicked()
        vm.dismissConfirm()
        assertThat(vm.uiState.value.confirmingClear).isFalse()

        // confirmingClear 已复位时 confirmClear 是 no-op
        vm.confirmClear()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.clearAll() }
    }
}
