package com.stock.dividend.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
class TransactionHistoryViewModelTest {

    @Before
    fun setUp() {
        // viewModelScope 需要 Main dispatcher：显式安装（§6 约定）。
        // 此前缺失时依赖同 JVM 其他测试类恰好装过 Main 的副作用，CI 类顺序不同即挂。
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun tx(id: Long, code: String, type: String, shares: Int, price: Double, date: String, note: String? = null) =
        TransactionEntity(
            id = id, stockCode = code, type = type, shares = shares, price = price, date = date, note = note
        )

    private fun stock(code: String, name: String) = StockEntity(
        code = code, name = name, marketCode = "1", shares = 0, costPerShare = 0.0
    )

    @Test
    fun `combines transactions with stock names and sorts by date desc`() = runTest {
        val txRepo = mockk<TransactionRepository>()
        val planeMock = mockk<MarketDataPlane>()
        coEvery { txRepo.observeAll() } returns flowOf(
            listOf(
                tx(1, "sh.600036", "BUY", 100, 10.0, "2026-01-01"),
                tx(2, "sh.600036", "SELL", 50, 12.0, "2026-03-01"),
                tx(3, "sz.000001", "BUY", 200, 8.0, "2026-02-01")
            )
        )
        coEvery { planeMock.observeAllStocks() } returns flowOf(
            listOf(stock("sh.600036", "招商银行"), stock("sz.000001", "平安银行"))
        )

        val vm = TransactionHistoryViewModel(txRepo, planeMock)
        vm.uiState.test {
            // 跳过初始空态，等到含数据的发射
            var state = awaitItem()
            if (state.items.isEmpty()) state = awaitItem()
            assertThat(state.items).hasSize(3)
            // 按日期倒序：3 月 → 2 月 → 1 月
            assertThat(state.items[0].transaction.id).isEqualTo(2L)
            assertThat(state.items[1].transaction.id).isEqualTo(3L)
            assertThat(state.items[2].transaction.id).isEqualTo(1L)
            // 股票名已映射
            assertThat(state.items[0].stockName).isEqualTo("招商银行")
            assertThat(state.items[1].stockName).isEqualTo("平安银行")
            // 累计买入 = 100*10 + 200*8 = 2600；累计卖出 = 50*12 = 600
            assertThat(state.totalBuyAmount).isEqualTo(2600.0)
            assertThat(state.totalSellAmount).isEqualTo(600.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty transactions shows loading then empty with zero totals`() = runTest {
        val txRepo = mockk<TransactionRepository>()
        val planeMock = mockk<MarketDataPlane>()
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        coEvery { planeMock.observeAllStocks() } returns flowOf(emptyList())

        val vm = TransactionHistoryViewModel(txRepo, planeMock)
        vm.uiState.test {
            var state = awaitItem()
            // 第一帧 isLoading=true；随后 observeAll 发空列表 → isLoading=false
            if (state.isLoading) state = awaitItem()
            assertThat(state.items).isEmpty()
            assertThat(state.isLoading).isFalse()
            assertThat(state.totalBuyAmount).isEqualTo(0.0)
            assertThat(state.totalSellAmount).isEqualTo(0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirmNote persists trimmed note and clears dialog`() = runTest {
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val planeMock = mockk<MarketDataPlane>(relaxed = true)
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        coEvery { planeMock.observeAllStocks() } returns flowOf(emptyList())
        coEvery { txRepo.updateTransaction(any()) } returns Unit

        val vm = TransactionHistoryViewModel(txRepo, planeMock)
        val original = tx(1, "sh.600036", "BUY", 100, 10.0, "2026-01-01")
        vm.showNoteDialog(original)
        vm.onNoteChanged("  贪婪追高  ")
        vm.confirmNote()
        advanceUntilIdle()

        coVerify {
            txRepo.updateTransaction(match { it.id == 1L && it.note == "贪婪追高" })
        }
        assertThat(vm.uiState.value.showNoteDialog).isFalse()
    }

    @Test
    fun `confirmNote stores null for blank note`() = runTest {
        val txRepo = mockk<TransactionRepository>(relaxed = true)
        val planeMock = mockk<MarketDataPlane>(relaxed = true)
        coEvery { txRepo.observeAll() } returns flowOf(emptyList())
        coEvery { planeMock.observeAllStocks() } returns flowOf(emptyList())
        coEvery { txRepo.updateTransaction(any()) } returns Unit

        val vm = TransactionHistoryViewModel(txRepo, planeMock)
        val original = tx(1, "sh.600036", "BUY", 100, 10.0, "2026-01-01", note = "旧备注")
        vm.showNoteDialog(original)
        vm.onNoteChanged("   ") // 纯空白
        vm.confirmNote()
        advanceUntilIdle()

        coVerify {
            txRepo.updateTransaction(match { it.id == 1L && it.note == null })
        }
    }

    @Test
    fun `note renders on item when present`() = runTest {
        val txRepo = mockk<TransactionRepository>()
        val planeMock = mockk<MarketDataPlane>()
        coEvery { txRepo.observeAll() } returns flowOf(
            listOf(tx(1, "sh.600036", "BUY", 100, 10.0, "2026-01-01", note = "首次建仓"))
        )
        coEvery { planeMock.observeAllStocks() } returns flowOf(listOf(stock("sh.600036", "招商银行")))

        val vm = TransactionHistoryViewModel(txRepo, planeMock)
        vm.uiState.test {
            var state = awaitItem()
            if (state.items.isEmpty()) state = awaitItem()
            assertThat(state.items[0].transaction.note).isEqualTo("首次建仓")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
