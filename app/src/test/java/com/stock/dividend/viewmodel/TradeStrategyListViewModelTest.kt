package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.TradeStrategyDao
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TradeStrategyListViewModelTest {

    @Before
    fun setUp() {
        // viewModelScope 需要 Main dispatcher：显式安装（§6 约定）。
        // 此前缺失时依赖同 JVM 其他测试类恰好装过 Main 的副作用，CI 类顺序不同即挂。
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `observeAll renders items`() = runTest {
        val dao = mockk<TradeStrategyDao>()
        coEvery { dao.observeAll() } returns flowOf(
            listOf(
                TradeStrategyEntity("1", "招商银行", "BUY", "r", "[]", null, null, "ocr")
            )
        )
        val vm = TradeStrategyListViewModel(dao)
        // WhileSubscribed(5000) 需订阅才启动上游；Turbine 订阅后取发射值
        vm.uiState.test {
            // 跳过初始空态，等到含数据的发射
            val state = awaitItem()
            if (state.items.isEmpty()) {
                val next = awaitItem()
                assertThat(next.items).hasSize(1)
                assertThat(next.items[0].targetText).isEqualTo("招商银行")
            } else {
                assertThat(state.items).hasSize(1)
                assertThat(state.items[0].targetText).isEqualTo("招商银行")
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `archive calls dao updateStatus`() = runTest {
        val dao = mockk<TradeStrategyDao>(relaxed = true)
        val vm = TradeStrategyListViewModel(dao)
        vm.archive("1")
        advanceUntilIdle()
        coVerify { dao.updateStatus("1", "ARCHIVED", any()) }
    }

    @Test
    fun `delete calls dao delete`() = runTest {
        val dao = mockk<TradeStrategyDao>(relaxed = true)
        val vm = TradeStrategyListViewModel(dao)
        vm.delete("1")
        advanceUntilIdle()
        coVerify { dao.delete("1") }
    }
}
