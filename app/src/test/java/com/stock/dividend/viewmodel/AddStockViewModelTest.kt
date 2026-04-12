package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.StockSearchResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddStockViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val dividendRepository: DividendRepository = mockk()

    private lateinit var viewModel: AddStockViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = AddStockViewModel(stockRepository, dividendRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addStock shows error with canRetry when dividend fetch fails`() = runTest {
        val result = StockSearchResult("sz.000001", "平安银行", "0")
        coEvery { stockRepository.addStock(result) } returns Result.success(Unit)
        coEvery { dividendRepository.fetchAndCacheDividends("sz.000001", "000001") } returns
            Result.failure(Exception("网络连接超时，请重试"))

        viewModel.addStock(result)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("网络连接超时，请重试")
        assertThat(viewModel.uiState.value.canRetry).isTrue()
        assertThat(viewModel.uiState.value.addedStock).isEqualTo("平安银行")
    }

    @Test
    fun `addStock shows success on valid flow`() = runTest {
        val result = StockSearchResult("sz.000001", "平安银行", "0")
        coEvery { stockRepository.addStock(result) } returns Result.success(Unit)
        coEvery { dividendRepository.fetchAndCacheDividends("sz.000001", "000001") } returns
            Result.success(Unit)

        viewModel.addStock(result)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.addedStock).isEqualTo("平安银行")
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.canRetry).isFalse()
    }

    @Test
    fun `addStock shows error with canRetry when addStock fails`() = runTest {
        val result = StockSearchResult("sz.000001", "平安银行", "0")
        coEvery { stockRepository.addStock(result) } returns
            Result.failure(Exception("添加失败，请重试"))

        viewModel.addStock(result)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("添加失败，请重试")
        assertThat(viewModel.uiState.value.canRetry).isTrue()
        assertThat(viewModel.uiState.value.addedStock).isNull()
    }

    @Test
    fun `retrySearch re-triggers last search`() = runTest {
        coEvery { stockRepository.searchStocks("平安银行") } returns
            Result.success(listOf(StockSearchResult("sz.000001", "平安银行", "0")))

        // Simulate search failure first
        coEvery { stockRepository.searchStocks("平安银行") } returns
            Result.failure(Exception("搜索失败"))

        viewModel.onSearchQueryChanged("平安银行")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.uiState.value.canRetry).isTrue()

        // Now make search succeed for retry
        coEvery { stockRepository.searchStocks("平安银行") } returns
            Result.success(listOf(StockSearchResult("sz.000001", "平安银行", "0")))

        viewModel.retrySearch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.searchResults).hasSize(1)
    }

    @Test
    fun `retryAddStock re-triggers last add`() = runTest {
        val result = StockSearchResult("sz.000001", "平安银行", "0")

        // First attempt fails
        coEvery { stockRepository.addStock(result) } returns
            Result.failure(Exception("添加失败，请重试"))

        viewModel.addStock(result)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNotNull()

        // Retry succeeds
        coEvery { stockRepository.addStock(result) } returns Result.success(Unit)
        coEvery { dividendRepository.fetchAndCacheDividends("sz.000001", "000001") } returns
            Result.success(Unit)

        viewModel.retryAddStock()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.addedStock).isEqualTo("平安银行")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `search error sets canRetry to true`() = runTest {
        coEvery { stockRepository.searchStocks("测试") } returns
            Result.failure(Exception("搜索失败"))

        viewModel.onSearchQueryChanged("测试")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.canRetry).isTrue()
        assertThat(viewModel.uiState.value.error).isEqualTo("搜索失败")
    }

    @Test
    fun `successful search clears canRetry`() = runTest {
        coEvery { stockRepository.searchStocks("平安银行") } returns
            Result.success(listOf(StockSearchResult("sz.000001", "平安银行", "0")))

        viewModel.onSearchQueryChanged("平安银行")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.canRetry).isFalse()
        assertThat(viewModel.uiState.value.searchResults).hasSize(1)
    }

    @Test
    fun `onSearchQueryChanged resets canRetry`() = runTest {
        // Set up error state
        coEvery { stockRepository.searchStocks("测试") } returns
            Result.failure(Exception("搜索失败"))

        viewModel.onSearchQueryChanged("测试")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.canRetry).isTrue()

        // New query resets immediately (before debounce triggers search)
        coEvery { stockRepository.searchStocks("新查询") } returns
            Result.success(emptyList())
        viewModel.onSearchQueryChanged("新查询")
        assertThat(viewModel.uiState.value.canRetry).isFalse()
    }

    @Test
    fun `onSearchQueryChanged with blank query clears results`() = runTest {
        // First search with results
        coEvery { stockRepository.searchStocks("平安银行") } returns
            Result.success(listOf(StockSearchResult("sz.000001", "平安银行", "0")))

        viewModel.onSearchQueryChanged("平安银行")
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.searchResults).hasSize(1)

        // Blank query clears results immediately
        viewModel.onSearchQueryChanged("")
        assertThat(viewModel.uiState.value.searchResults).isEmpty()
    }
}
