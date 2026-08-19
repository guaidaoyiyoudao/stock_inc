package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.CacheKind
import com.stock.dividend.data.repository.CacheManagementRepository
import com.stock.dividend.data.repository.CacheStats
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class CacheManagementViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val repository = mockk<CacheManagementRepository>()
    private val plane = mockk<MarketDataPlane>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.loadStats() } returns CacheKind.entries.map {
            CacheStats(it, entries = 3L)
        }
        coEvery { repository.clear(any()) } just Runs
        coEvery { repository.clearAll() } just Runs
        io.mockk.every { plane.clearSessionCaches() } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads stats for all kinds`() = runTest {
        val vm = CacheManagementViewModel(repository, plane)
        advanceUntilIdle()

        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.entries).hasSize(CacheKind.entries.size)
        assertThat(vm.uiState.value.entries.first { it.kind == CacheKind.KLINE }.entries).isEqualTo(3L)
    }

    @Test
    fun `clear click opens confirmation and dismiss closes it`() = runTest {
        val vm = CacheManagementViewModel(repository, plane)
        advanceUntilIdle()

        vm.onClearClicked(CacheKind.KLINE)
        assertThat(vm.uiState.value.confirming).isEqualTo(CacheKind.KLINE)
        assertThat(vm.uiState.value.confirmingAll).isFalse()

        vm.dismissConfirm()
        assertThat(vm.uiState.value.confirming).isNull()
    }

    @Test
    fun `confirmClear clears repo resets plane session caches and reloads stats`() = runTest {
        val vm = CacheManagementViewModel(repository, plane)
        advanceUntilIdle()
        // 清理后重新加载时计数归零，断言刷新确实发生
        coEvery { repository.loadStats() } returns CacheKind.entries.map { CacheStats(it, 0L) }

        vm.onClearClicked(CacheKind.DIVIDENDS)
        vm.confirmClear()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.clear(CacheKind.DIVIDENDS) }
        verify(exactly = 1) { plane.clearSessionCaches() }
        assertThat(vm.uiState.value.confirming).isNull()
        assertThat(vm.uiState.value.entries.first { it.kind == CacheKind.DIVIDENDS }.entries).isEqualTo(0L)
        assertThat(vm.uiState.value.message).contains(CacheKind.DIVIDENDS.label)
    }

    @Test
    fun `clear all flow asks confirmation then clears everything`() = runTest {
        val vm = CacheManagementViewModel(repository, plane)
        advanceUntilIdle()

        vm.onClearAllClicked()
        assertThat(vm.uiState.value.confirmingAll).isTrue()

        vm.confirmClearAll()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.clearAll() }
        coVerify(exactly = 0) { repository.clear(any()) }
        verify(exactly = 1) { plane.clearSessionCaches() }
        assertThat(vm.uiState.value.confirmingAll).isFalse()
        assertThat(vm.uiState.value.message).isNotNull()
    }

    @Test
    fun `load failure still resets loading without crash`() = runTest {
        coEvery { repository.loadStats() } throws IllegalStateException("db")
        val vm = CacheManagementViewModel(repository, plane)
        advanceUntilIdle()

        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.entries).isEmpty()
    }

    @Test
    fun `consumeMessage clears transient message`() = runTest {
        val vm = CacheManagementViewModel(repository, plane)
        advanceUntilIdle()
        vm.onClearClicked(CacheKind.PRICE)
        vm.confirmClear()
        advanceUntilIdle()
        assertThat(vm.uiState.value.message).isNotNull()

        vm.consumeMessage()
        assertThat(vm.uiState.value.message).isNull()
    }
}
