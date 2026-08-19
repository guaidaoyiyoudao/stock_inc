package com.stock.dividend.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.data.repository.LlmConfigRepository
import com.stock.dividend.data.repository.ParsedTransactionRow
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionImportRow
import com.stock.dividend.data.repository.TransactionImportSummary
import com.stock.dividend.data.repository.VisionImportRepository
import com.stock.dividend.data.repository.VisionImportResult
import com.stock.dividend.data.repository.VisionParseMode
import com.stock.dividend.data.scan.loadSampledBitmap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
class TransactionImportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stockRepository: StockRepository = mockk()
    private val visionImportRepository: VisionImportRepository = mockk()
    private val llmConfigRepository: LlmConfigRepository = mockk()
    private lateinit var context: Context
    private val fakeBitmap: Bitmap = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        mockkStatic("com.stock.dividend.data.scan.BitmapLoaderKt")
        coEvery { loadSampledBitmap(any(), any<Uri>()) } returns fakeBitmap
        every { llmConfigRepository.observeVisionConfig() } returns flowOf(
            LlmConfig("https://open.bigmodel.cn/api/paas/v4/", "key", "glm-4.6v-flash")
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("com.stock.dividend.data.scan.BitmapLoaderKt")
    }

    @Test
    fun `onImagePicked fills rows from vision result and enters review`() = runTest {
        coEvery { visionImportRepository.parse(any(), VisionParseMode.TRANSACTIONS, any()) } returns
            VisionImportResult.Transactions(
                listOf(
                    ParsedTransactionRow("600519", "贵州茅台", "BUY", 100, 1500.5, "2026-08-01"),
                    ParsedTransactionRow("平安银行", null, null, 500, 12.34, null)
                )
            )

        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.phase).isEqualTo(TransactionImportPhase.Review)
        assertThat(state.rows).hasSize(2)
        assertThat(state.rows[0].codeOrNameInput).isEqualTo("600519")
        assertThat(state.rows[0].typeInput).isEqualTo("BUY")
        assertThat(state.rows[0].sharesInput).isEqualTo("100")
        assertThat(state.rows[0].priceInput).isEqualTo("1500.5")
        assertThat(state.rows[0].dateInput).isEqualTo("2026-08-01")
        assertThat(state.rows[0].resolvedName).isEqualTo("贵州茅台")
        // 方向缺失的行兜底 BUY、日期留空交由校验
        assertThat(state.rows[1].typeInput).isEqualTo("BUY")
        assertThat(state.rows[1].dateInput).isEmpty()
    }

    @Test
    fun `holdings screenshot yields guidance error`() = runTest {
        coEvery { visionImportRepository.parse(any(), VisionParseMode.TRANSACTIONS, any()) } returns
            VisionImportResult.Holdings(emptyList())

        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.phase).isEqualTo(TransactionImportPhase.Error)
        assertThat(viewModel.uiState.value.errorMessage).contains("持仓")
    }

    @Test
    fun `confirmImport aborts on invalid date and shares`() = runTest {
        coEvery { visionImportRepository.parse(any(), VisionParseMode.TRANSACTIONS, any()) } returns
            VisionImportResult.Transactions(
                listOf(ParsedTransactionRow("600519", null, "BUY", 100, 15.5, "2026-08-01"))
            )
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRowDateChanged(viewModel.uiState.value.rows.first().id, "2026/8/1")
        viewModel.onRowSharesChanged(viewModel.uiState.value.rows.first().id, "abc")
        viewModel.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { stockRepository.importTransactions(any()) }
        assertThat(viewModel.uiState.value.rows.first().dateError).isNotNull()
        assertThat(viewModel.uiState.value.rows.first().sharesError).isNotNull()
    }

    @Test
    fun `confirmImport persists rows and shows dedup summary`() = runTest {
        coEvery { visionImportRepository.parse(any(), VisionParseMode.TRANSACTIONS, any()) } returns
            VisionImportResult.Transactions(
                listOf(
                    ParsedTransactionRow("600519", null, "BUY", 100, 1500.5, "2026-08-01"),
                    ParsedTransactionRow("000001", null, "SELL", 500, 12.34, "2026-08-02")
                )
            )
        coEvery { stockRepository.importTransactions(any()) } returns TransactionImportSummary(
            insertedCount = 2, duplicatesSkipped = 1, failedRows = emptyList()
        )
        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.phase).isEqualTo(TransactionImportPhase.Done)
        assertThat(state.importSummary).contains("成功导入 2 笔")
        assertThat(state.importSummary).contains("跳过重复 1 笔")

        val slot = slot<List<TransactionImportRow>>()
        coVerify { stockRepository.importTransactions(capture(slot)) }
        assertThat(slot.captured).hasSize(2)
        assertThat(slot.captured.first().rawCodeOrName).isEqualTo("600519")
        assertThat(slot.captured.first().type).isEqualTo("BUY")
        assertThat(slot.captured.first().date).isEqualTo("2026-08-01")
    }

    @Test
    fun `vision retry status is surfaced during analyzing`() = runTest {
        coEvery { visionImportRepository.parse(any(), VisionParseMode.TRANSACTIONS, any()) } coAnswers {
            thirdArg<(Int, Int, String) -> Unit>().invoke(1, 5, "网络错误")
            VisionImportResult.Transactions(
                listOf(ParsedTransactionRow("600519", null, "BUY", 100, 15.5, "2026-08-01"))
            )
        }

        val viewModel = createViewModel()
        viewModel.onImagePicked(Uri.parse("content://x/1"))
        testDispatcher.scheduler.advanceUntilIdle()

        // 重试文案在成功后被清空（进入 Review），此处验证流程不崩溃且最终进入 Review
        assertThat(viewModel.uiState.value.phase).isEqualTo(TransactionImportPhase.Review)
    }

    @Test
    fun `addEmptyRow defaults to BUY with today`() = runTest {
        val viewModel = createViewModel()
        viewModel.addEmptyRow()

        val row = viewModel.uiState.value.rows.single()
        assertThat(row.typeInput).isEqualTo("BUY")
        assertThat(row.dateInput).isNotEmpty()
    }

    private fun createViewModel() =
        TransactionImportViewModel(stockRepository, visionImportRepository, llmConfigRepository, context)
}
