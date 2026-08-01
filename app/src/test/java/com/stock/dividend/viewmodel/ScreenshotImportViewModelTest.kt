package com.stock.dividend.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.repository.ScreenshotStrategy
import com.stock.dividend.data.repository.ScreenshotStrategyRepository
import com.stock.dividend.data.repository.ScreenshotStrategyState
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.scan.OcrElement
import com.stock.dividend.data.scan.TextRecognitionService
import com.stock.dividend.data.scan.loadSampledBitmap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
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
class ScreenshotImportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val textRecognitionService: TextRecognitionService = mockk()
    private val llmRepo: ScreenshotStrategyRepository = mockk()
    private val strategyRepo: TradeStrategyRepository = mockk(relaxed = true)
    private lateinit var context: Context
    private val fakeBitmap: Bitmap = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        mockkStatic("com.stock.dividend.data.scan.BitmapLoaderKt")
        coEvery { loadSampledBitmap(any(), any<Uri>()) } returns fakeBitmap
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("com.stock.dividend.data.scan.BitmapLoaderKt")
    }

    private fun createViewModel() = ScreenshotImportViewModel(
        textRecognitionService, llmRepo, strategyRepo, context
    )

    @Test
    fun `onImagePicked runs OCR and stops at ReviewOcr`() = runTest {
        coEvery { textRecognitionService.recognize(any()) } returns listOf(
            OcrElement("招商银行 买入", 0f, 0f, 100f, 10f)
        )
        val vm = createViewModel()
        vm.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(ScreenshotImportPhase.ReviewOcr)
        assertThat(vm.uiState.value.editableOcrText).contains("招商银行")
    }

    @Test
    fun `startAnalysis success enters ReviewStrategy with LLM fields`() = runTest {
        coEvery { textRecognitionService.recognize(any()) } returns listOf(
            OcrElement("招商银行", 0f, 0f, 10f, 10f)
        )
        coEvery { llmRepo.analyze(any()) } returns ScreenshotStrategyState.Success(
            ScreenshotStrategy(
                targetText = "招商银行",
                direction = ScreenshotStrategy.StrategyDirection.BUY,
                reasoning = "ROE高",
                risks = listOf("息差"),
                validUntil = null
            )
        )
        val vm = createViewModel()
        vm.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        advanceUntilIdle()
        vm.startAnalysis()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertThat(s.phase).isEqualTo(ScreenshotImportPhase.ReviewStrategy)
        assertThat(s.editableStrategy!!.targetText).isEqualTo("招商银行")
        assertThat(s.editableStrategy!!.direction).isEqualTo(ScreenshotStrategy.StrategyDirection.BUY)
        assertThat(s.editableStrategy!!.risks).containsExactly("息差")
    }

    @Test
    fun `startAnalysis no strategy stays ReviewOcr with error`() = runTest {
        coEvery { textRecognitionService.recognize(any()) } returns listOf(OcrElement("x", 0f, 0f, 10f, 10f))
        coEvery { llmRepo.analyze(any()) } returns ScreenshotStrategyState.NoStrategy("no")
        val vm = createViewModel()
        vm.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        advanceUntilIdle()
        vm.startAnalysis()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(ScreenshotImportPhase.ReviewOcr)
        assertThat(vm.uiState.value.analysisError).isNotNull()
    }

    @Test
    fun `confirmSave persists entity without stockCode`() = runTest {
        coEvery { textRecognitionService.recognize(any()) } returns listOf(OcrElement("招商银行", 0f, 0f, 10f, 10f))
        coEvery { llmRepo.analyze(any()) } returns ScreenshotStrategyState.Success(
            ScreenshotStrategy("招商银行", ScreenshotStrategy.StrategyDirection.BUY, "r", emptyList(), null)
        )
        val entitySlot = slot<TradeStrategyEntity>()
        coEvery { strategyRepo.upsert(capture(entitySlot)) } returns Unit

        val vm = createViewModel()
        vm.onImagePicked(Uri.parse("content://media/external/images/media/1"))
        advanceUntilIdle()
        vm.startAnalysis()
        advanceUntilIdle()
        vm.confirmSave()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(ScreenshotImportPhase.Done)
        coVerify { strategyRepo.upsert(any()) }
        val saved = entitySlot.captured
        assertThat(saved.targetText).isEqualTo("招商银行")
        assertThat(saved.direction).isEqualTo("BUY")
        // 策略全局：无 stockCode 字段，断言实体本身就是无关联的
        assertThat(saved.rawOcrText).contains("招商银行")
    }
}
