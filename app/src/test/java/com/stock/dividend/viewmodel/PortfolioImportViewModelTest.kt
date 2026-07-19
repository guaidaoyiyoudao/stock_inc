package com.stock.dividend.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.ImportRow
import com.stock.dividend.data.repository.ImportSummary
import com.stock.dividend.data.repository.StockRepository
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
    private val textRecognitionService: TextRecognitionService = mockk()
    private val context: Context = mockk(relaxed = true)
    private val fakeBitmap: Bitmap = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // loadSampledBitmap 是顶层 suspend 函数，mockkStatic 替换为返回假 bitmap
        mockkStatic("com.stock.dividend.data.scan.BitmapLoaderKt")
        coEvery { loadSampledBitmap(any(), any<Uri>()) } returns fakeBitmap
        // android.net.Uri 在纯 JVM 测试里是 stub，必须 mock
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
    fun `onImagePicked fills rows from recognized text and enters review`() = runTest {
        val ocrText = """
            证券名称 证券代码 持股数 成本价
            贵州茅台 600519 100 1500.00
            平安银行 000001 1000 12.34
        """.trimIndent()
        coEvery { textRecognitionService.recognize(any()) } returns ocrText

        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.phase).isEqualTo(ImportPhase.Review)
        assertThat(state.rows).hasSize(2)
        assertThat(state.rows[0].codeOrNameInput).isEqualTo("600519")
        assertThat(state.rows[0].sharesInput).isEqualTo("100")
        assertThat(state.rows[0].costPerShareInput).isEqualTo("1500")
        assertThat(state.rows[1].codeOrNameInput).isEqualTo("000001")
    }

    @Test
    fun `onImagePicked enters error when nothing parseable found`() = runTest {
        coEvery { textRecognitionService.recognize(any()) } returns "仅有一些无意义文本"

        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.phase).isEqualTo(ImportPhase.Error)
        assertThat(state.errorMessage).isNotNull()
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
        seedRecognized("贵州茅台 600519 100 1500.00")
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        // 人为清空某行的代码
        viewModel.onRowCodeOrNameChanged(viewModel.uiState.value.rows.first().id, "")
        viewModel.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        // 校验失败 → 不调 importHoldings，留在 Review
        coVerify(exactly = 0) { stockRepository.importHoldings(any()) }
        assertThat(viewModel.uiState.value.phase).isEqualTo(ImportPhase.Review)
        assertThat(viewModel.uiState.value.rows.first().codeOrNameError).isNotNull()
    }

    @Test
    fun `confirmImport rejects non-positive shares`() = runTest {
        seedRecognized("贵州茅台 600519 100 1500.00")
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
        seedRecognized("贵州茅台 600519 100 1500.00")
        coEvery { stockRepository.importHoldings(any()) } returns ImportSummary(
            succeeded = listOf("sh.600519"),
            failed = emptyList()
        )
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.phase).isEqualTo(ImportPhase.Done)
        assertThat(state.importSummary).contains("成功导入 1 只")
        val slot = mutableListOf<List<ImportRow>>()
        coVerify { stockRepository.importHoldings(capture(slot)) }
        assertThat(slot.first()).hasSize(1)
        assertThat(slot.first().first().rawCodeOrName).isEqualTo("600519")
        assertThat(slot.first().first().shares).isEqualTo(100)
    }

    @Test
    fun `confirmImport surfaces partial failures in summary`() = runTest {
        seedRecognized("贵州茅台 600519 100 1500.00")
        coEvery { stockRepository.importHoldings(any()) } returns ImportSummary(
            succeeded = emptyList(),
            failed = listOf(ImportRow("600519", 100, 1500.0))
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
        seedRecognized("贵州茅台 600519 100 1500.00")
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()
        val id = viewModel.uiState.value.rows.first().id

        viewModel.removeRow(id)

        assertThat(viewModel.uiState.value.rows).isEmpty()
    }

    private fun seedRecognized(text: String) {
        coEvery { textRecognitionService.recognize(any()) } returns text
    }

    private fun createViewModel() =
        PortfolioImportViewModel(stockRepository, textRecognitionService, context)
}
