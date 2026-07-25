package com.stock.dividend.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.ImportRow
import com.stock.dividend.data.repository.ImportSummary
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.scan.OcrElement
import com.stock.dividend.data.scan.TextRecognitionService
import com.stock.dividend.data.scan.loadSampledBitmap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
class PortfolioImportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val dividendRepository: DividendRepository = mockk(relaxed = true)
    private val textRecognitionService: TextRecognitionService = mockk()
    private val context: Context = mockk(relaxed = true)
    private val fakeBitmap: Bitmap = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic("com.stock.dividend.data.scan.BitmapLoaderKt")
        coEvery { loadSampledBitmap(any(), any<Uri>()) } returns fakeBitmap
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("com.stock.dividend.data.scan.BitmapLoaderKt")
        unmockkStatic(Uri::class)
    }

    @Test
    fun `onImagePicked fills rows from recognized elements and enters review`() = runTest {
        seedRecognized(
            names = listOf("贵州茅台", "平安银行"),
            shares = listOf(100, 1000),
            prices = listOf(1500.0, 12.34)
        )

        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.phase).isEqualTo(ImportPhase.Review)
        assertThat(state.rows).hasSize(2)
        assertThat(state.rows[0].codeOrNameInput).isEqualTo("贵州茅台")
        assertThat(state.rows[0].sharesInput).isEqualTo("100")
        assertThat(state.rows[0].costPerShareInput).isEqualTo("1500")
        assertThat(state.rows[1].codeOrNameInput).isEqualTo("平安银行")
    }

    @Test
    fun `onImagePicked enters error when nothing parseable found`() = runTest {
        // 纯噪声元素（无名称、无数字）
        coEvery { textRecognitionService.recognize(any()) } returns listOf(
            OcrElement("###", 50f, 100f, 130f, 140f),
            OcrElement("@@@", 50f, 160f, 130f, 200f)
        )

        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.phase).isEqualTo(ImportPhase.Error)
    }

    @Test
    fun `onImagePicked enters error on OCR exception`() = runTest {
        coEvery { textRecognitionService.recognize(any()) } throws RuntimeException("model load failed")

        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.phase).isEqualTo(ImportPhase.Error)
    }

    @Test
    fun `confirmImport aborts when a row has blank code`() = runTest {
        seedRecognized(
            names = listOf("贵州茅台"),
            shares = listOf(100),
            prices = listOf(1500.0)
        )
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRowCodeOrNameChanged(viewModel.uiState.value.rows.first().id, "")
        viewModel.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { stockRepository.importHoldings(any()) }
        assertThat(viewModel.uiState.value.phase).isEqualTo(ImportPhase.Review)
        assertThat(viewModel.uiState.value.rows.first().codeOrNameError).isNotNull()
    }

    @Test
    fun `confirmImport rejects non-positive shares`() = runTest {
        seedRecognized(
            names = listOf("贵州茅台"),
            shares = listOf(100),
            prices = listOf(1500.0)
        )
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRowSharesChanged(viewModel.uiState.value.rows.first().id, "0")
        viewModel.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { stockRepository.importHoldings(any()) }
        assertThat(viewModel.uiState.value.rows.first().sharesError).isNotNull()
    }

    @Test
    fun `confirmImport persists and shows summary on success`() = runTest {
        seedRecognized(
            names = listOf("贵州茅台", "平安银行"),
            shares = listOf(100, 50),
            prices = listOf(1500.0, 12.34)
        )
        coEvery { stockRepository.importHoldings(any()) } returns ImportSummary(
            succeeded = listOf("sh.600519", "sz.000001"),
            failed = emptyList()
        )
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.phase).isEqualTo(ImportPhase.Done)
        assertThat(state.importSummary).contains("成功导入 2 只")
        val slot = mutableListOf<List<ImportRow>>()
        coVerify { stockRepository.importHoldings(capture(slot)) }
        assertThat(slot.first()).hasSize(2)
        assertThat(slot.first().first().rawCodeOrName).isEqualTo("贵州茅台")
        assertThat(slot.first().first().shares).isEqualTo(100)
    }

    @Test
    fun `confirmImport surfaces partial failures in summary`() = runTest {
        seedRecognized(
            names = listOf("贵州茅台"),
            shares = listOf(100),
            prices = listOf(1500.0)
        )
        coEvery { stockRepository.importHoldings(any()) } returns ImportSummary(
            succeeded = emptyList(),
            failed = listOf(ImportRow("贵州茅台", 100, 1500.0))
        )
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.phase).isEqualTo(ImportPhase.Done)
        assertThat(viewModel.uiState.value.importSummary).contains("失败 1 只")
    }

    @Test
    fun `removeRow removes the row from state`() = runTest {
        seedRecognized(
            names = listOf("贵州茅台", "平安银行"),
            shares = listOf(100, 50),
            prices = listOf(1500.0, 12.34)
        )
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()
        val firstId = viewModel.uiState.value.rows.first().id

        viewModel.removeRow(firstId)

        assertThat(viewModel.uiState.value.rows).hasSize(1)
        assertThat(viewModel.uiState.value.rows.first().codeOrNameInput).isEqualTo("平安银行")
    }

    /** 模拟 OCR 返回分块布局：名称、股数、价格各成一列，Y 对齐。 */
    private fun seedRecognized(
        names: List<String>,
        shares: List<Int>,
        prices: List<Double>
    ) {
        val elements = mutableListOf<OcrElement>()
        names.forEachIndexed { i, name ->
            val y = 100f + i * 60f
            elements.add(OcrElement(name, 50f, y, 130f, y + 40f))
            elements.add(OcrElement(shares[i].toString(), 400f, y, 480f, y + 40f))
            if (i < prices.size) {
                elements.add(OcrElement(prices[i].toString(), 600f, y, 680f, y + 40f))
            }
        }
        coEvery { textRecognitionService.recognize(any()) } returns elements
    }

    private fun createViewModel() =
        PortfolioImportViewModel(stockRepository, dividendRepository, textRecognitionService, context)
}
