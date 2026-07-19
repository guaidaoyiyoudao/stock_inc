package com.stock.dividend.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.scan.HoldingScreenshotParser
import com.stock.dividend.data.scan.TextRecognitionService
import com.stock.dividend.data.scan.loadSampledBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class PreprocessMode(val label: String) {
    RAW("原图"),
    SCALE_2X("放大2x"),
    GRAYSCALE("灰度"),
    BINARIZE("二值化"),
    CONTRAST("高对比度"),
    SCALE_GRAY("放大2x+灰度")
}

@Stable
data class OcrDebugUiState(
    val mode: PreprocessMode = PreprocessMode.RAW,
    val processedPreview: Bitmap? = null,
    val rawText: String? = null,
    val parsedRows: List<com.stock.dividend.data.scan.ParsedHoldingRow> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastUri: Uri? = null
)

@HiltViewModel
class OcrDebugViewModel @Inject constructor(
    private val textRecognitionService: TextRecognitionService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(OcrDebugUiState())
    val uiState: StateFlow<OcrDebugUiState> = _uiState.asStateFlow()

    private var originalBitmap: Bitmap? = null

    fun selectMode(mode: PreprocessMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    /**
     * 由 UI 选完图后调用。
     */
    fun onImagePicked(uri: Uri) {
        originalBitmap = null
        _uiState.update {
            it.copy(lastUri = uri, isLoading = true, error = null, rawText = null, parsedRows = emptyList())
        }
        viewModelScope.launch {
            try {
                originalBitmap = loadSampledBitmap(context, uri)
                runOcr()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "加载图片失败：${e.message}") }
            }
        }
    }

    /** 不换图，用当前模式和当前图重新跑一次 OCR。 */
    fun rerun() {
        if (originalBitmap == null) {
            _uiState.update { it.copy(error = "还没有图片") }
            return
        }
        viewModelScope.launch { runOcr() }
    }

    private suspend fun runOcr() = withContext(Dispatchers.Default) {
        val src = originalBitmap ?: run {
            _uiState.update { it.copy(isLoading = false, error = "无图片") }
            return@withContext
        }
        _uiState.update { it.copy(isLoading = true) }
        try {
            val processed = preprocess(src, _uiState.value.mode)
            _uiState.update { it.copy(processedPreview = processed) }
            val elements = textRecognitionService.recognize(processed)
            val rawText = elements.joinToString("\n") { it.text }
            val rows = HoldingScreenshotParser.parseFromElements(elements)
            _uiState.update {
                it.copy(isLoading = false, rawText = rawText, parsedRows = rows)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = "OCR 失败：${e.message}") }
        }
    }

    /** 按模式做图像预处理。 */
    private fun preprocess(src: Bitmap, mode: PreprocessMode): Bitmap = when (mode) {
        PreprocessMode.RAW -> src
        PreprocessMode.SCALE_2X -> scale(src, 2f)
        PreprocessMode.GRAYSCALE -> grayscale(src)
        PreprocessMode.BINARIZE -> binarize(src)
        PreprocessMode.CONTRAST -> contrast(src, contrast = 2f, brightness = -50f)
        PreprocessMode.SCALE_GRAY -> grayscale(scale(src, 2f))
    }

    private fun scale(src: Bitmap, factor: Float): Bitmap {
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun contrast(src: Bitmap, contrast: Float, brightness: Float): Bitmap {
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun binarize(src: Bitmap, threshold: Int = 140): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            // ITU-R BT.601 亮度
            val gray = ((c shr 16 and 0xff) * 0.299 + (c shr 8 and 0xff) * 0.587 + (c and 0xff) * 0.114).toInt()
            pixels[i] = if (gray >= threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        out.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return out
    }
}
