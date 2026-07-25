package com.stock.dividend.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditHoldingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val transactionRepository: TransactionRepository = mockk()
    private val stockFlow = MutableStateFlow<StockEntity?>(null)
    private val transactions = mutableListOf<TransactionEntity>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { stockRepository.observeStock("sz.000001") } returns stockFlow
        every { stockRepository.observeTagsForStock("sz.000001") } returns MutableStateFlow(emptyList())
        every { stockRepository.observeAllTags() } returns MutableStateFlow(emptyList())
        coEvery { transactionRepository.getByStock("sz.000001") } answers { transactions.toList() }
        coEvery { stockRepository.updateShares("sz.000001", any()) } returns Unit
        coEvery { stockRepository.updateCostPerShare("sz.000001", any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `edit transaction updates date shares price and recalculates holding`() = runTest {
        transactions += TransactionEntity(
            id = 1L,
            stockCode = "sz.000001",
            type = "BUY",
            shares = 100,
            price = 10.0,
            date = "2026-05-01"
        )
        transactions += TransactionEntity(
            id = 2L,
            stockCode = "sz.000001",
            type = "SELL",
            shares = 20,
            price = 12.0,
            date = "2026-05-02"
        )
        coEvery { transactionRepository.updateTransaction(any()) } answers {
            val updated = firstArg<TransactionEntity>()
            transactions.replaceAll { if (it.id == updated.id) updated else it }
            Unit
        }

        val viewModel = EditHoldingViewModel(
            SavedStateHandle(mapOf("code" to "sz.000001")),
            stockRepository,
            transactionRepository
        )
        stockFlow.value = StockEntity("sz.000001", "平安银行", "0")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showEditTransactionDialog(transactions.first())
        viewModel.onEditDateChanged("2026-05-10")
        viewModel.onEditSharesChanged("150")
        viewModel.onEditPriceChanged("11.50")
        viewModel.confirmEditTransaction()
        testDispatcher.scheduler.advanceUntilIdle()

        val editedTransaction = viewModel.uiState.value.transactions.first { it.id == 1L }
        assertThat(editedTransaction.date).isEqualTo("2026-05-10")
        assertThat(editedTransaction.shares).isEqualTo(150)
        assertThat(editedTransaction.price).isEqualTo(11.5)
        assertThat(viewModel.uiState.value.totalShares).isEqualTo(130)
        assertThat(viewModel.uiState.value.avgCostPerShare).isEqualTo(11.5)
        assertThat(viewModel.uiState.value.showEditTransactionDialog).isFalse()
        assertThat(viewModel.uiState.value.editInputError).isNull()
    }
}
