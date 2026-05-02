package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.dao.TransactionDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.StockEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DividendIncomeRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private val incomeRecordDao: DividendIncomeRecordDao = mockk(relaxed = true)
    private val dividendDao: DividendDao = mockk(relaxed = true)
    private val stockDao: StockDao = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)

    private lateinit var repository: DividendIncomeRepository

    private val testStock = StockEntity(
        code = "sh.600000",
        name = "浦发银行",
        marketCode = "1",
        shares = 1000
    )

    @Before
    fun setUp() {
        repository = DividendIncomeRepository(incomeRecordDao, dividendDao, stockDao, transactionDao)
    }

    // --- Auto-generation tests ---

    @Test
    fun `generateMissingAutoRecords inserts records for dividends without existing auto records`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = "2024-07-10"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllIds() } returns emptyList()
        coEvery { stockDao.getByCode("sh.600000") } returns testStock

        repository.generateMissingAutoRecords()

        val recordsSlot = slot<List<DividendIncomeRecordEntity>>()
        coVerify { incomeRecordDao.insertAll(capture(recordsSlot)) }
        val records = recordsSlot.captured
        assertThat(records).hasSize(1)
        assertThat(records[0].id).isEqualTo("auto_sh.600000_2024-07-10")
        assertThat(records[0].amount).isWithin(0.001).of(246.0)
        assertThat(records[0].source).isEqualTo("auto")
        assertThat(records[0].year).isEqualTo(2024)
        assertThat(records[0].date).isEqualTo("2024-07-10")
    }

    @Test
    fun `generateMissingAutoRecords skips dividends with null exDividendDate`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = null
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllIds() } returns emptyList()

        repository.generateMissingAutoRecords()

        coVerify(exactly = 0) { incomeRecordDao.insertAll(any()) }
    }

    @Test
    fun `generateMissingAutoRecords skips when shares is zero`() = runTest {
        val zeroShareStock = testStock.copy(shares = 0)
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = "2024-07-10"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllIds() } returns emptyList()
        coEvery { stockDao.getByCode("sh.600000") } returns zeroShareStock

        repository.generateMissingAutoRecords()

        coVerify(exactly = 0) { incomeRecordDao.insertAll(any()) }
    }

    @Test
    fun `generateMissingAutoRecords does not insert when auto record already exists`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = "2024-07-10"
            )
        )
        val existingRecords = listOf(
            DividendIncomeRecordEntity(
                id = "auto_sh.600000_2024-07-10",
                stockCode = "sh.600000",
                year = 2024,
                date = "2024-07-10",
                amount = 246.0,
                source = "auto"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllIds() } returns listOf("auto_sh.600000_2024-07-10")

        repository.generateMissingAutoRecords()

        coVerify(exactly = 0) { incomeRecordDao.insertAll(any()) }
    }

    @Test
    fun `generateMissingAutoRecords handles multiple dividends per stock`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = "2024-07-10"
            ),
            DividendEntity(
                id = "sh.600000_2024-06-30",
                stockCode = "sh.600000",
                reportDate = "2024-06-30",
                cashPerShare = 0.100,
                exDividendDate = "2024-01-15"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllIds() } returns emptyList()
        coEvery { stockDao.getByCode("sh.600000") } returns testStock

        repository.generateMissingAutoRecords()

        val recordsSlot = slot<List<DividendIncomeRecordEntity>>()
        coVerify { incomeRecordDao.insertAll(capture(recordsSlot)) }
        assertThat(recordsSlot.captured).hasSize(2)
    }

    @Test
    fun `generateMissingAutoRecords skips when stock not found in stockDao`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.999999_2024-12-31",
                stockCode = "sh.999999",
                reportDate = "2024-12-31",
                cashPerShare = 0.1,
                exDividendDate = "2024-07-10"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllIds() } returns emptyList()
        coEvery { stockDao.getByCode("sh.999999") } returns null

        repository.generateMissingAutoRecords()

        coVerify(exactly = 0) { incomeRecordDao.insertAll(any()) }
    }

    // --- Correction tests ---

    @Test
    fun `correctRecord updates source to manual and amount`() = runTest {
        coEvery { incomeRecordDao.correctRecord(any(), any(), any(), any()) } returns Unit

        repository.correctRecord("auto_sh.600000_2024-07-10", 300.0, "实际到账300")

        coVerify {
            incomeRecordDao.correctRecord(
                "auto_sh.600000_2024-07-10",
                300.0,
                "实际到账300",
                any()
            )
        }
    }

    // --- Manual add tests ---

    @Test
    fun `addManualRecord creates record with correct fields`() = runTest {
        coEvery { incomeRecordDao.insert(any()) } returns Unit

        repository.addManualRecord(
            date = "2025-03-15",
            amount = 500.0,
            stockCode = "sh.600000",
            note = "港股通分红"
        )

        val recordSlot = slot<DividendIncomeRecordEntity>()
        coVerify { incomeRecordDao.insert(capture(recordSlot)) }
        val record = recordSlot.captured
        assertThat(record.source).isEqualTo("manual")
        assertThat(record.date).isEqualTo("2025-03-15")
        assertThat(record.amount).isWithin(0.001).of(500.0)
        assertThat(record.stockCode).isEqualTo("sh.600000")
        assertThat(record.note).isEqualTo("港股通分红")
        assertThat(record.year).isEqualTo(2025)
        assertThat(record.id).startsWith("manual_")
    }

    @Test
    fun `addManualRecord without stockCode creates null stockCode record`() = runTest {
        coEvery { incomeRecordDao.insert(any()) } returns Unit

        repository.addManualRecord(
            date = "2025-03-15",
            amount = 200.0,
            stockCode = null,
            note = null
        )

        val recordSlot = slot<DividendIncomeRecordEntity>()
        coVerify { incomeRecordDao.insert(capture(recordSlot)) }
        assertThat(recordSlot.captured.stockCode).isNull()
    }

    // --- Delete tests ---

    @Test
    fun `deleteManualRecord delegates to DAO`() = runTest {
        coEvery { incomeRecordDao.deleteManualRecord(any()) } returns 1

        repository.deleteManualRecord("manual_12345")

        coVerify { incomeRecordDao.deleteManualRecord("manual_12345") }
    }
}
