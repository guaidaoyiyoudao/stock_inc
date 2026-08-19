package com.stock.dividend.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.ImportRow
import com.stock.dividend.data.repository.ImportSummary
import com.stock.dividend.data.repository.LlmConfigRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.VisionImportRepository
import com.stock.dividend.data.repository.VisionImportResult
import com.stock.dividend.data.repository.VisionParseMode
import com.stock.dividend.data.scan.HoldingScreenshotParser
import com.stock.dividend.data.scan.TextRecognitionService
import com.stock.dividend.data.scan.loadSampledBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ImportPhase { Idle, LoadingImage, OcrRunning, Review, Importing, Done, Error }

/** 截图识别引擎：本地 ML Kit OCR（离线不上传） / AI 视觉模型（GLM-4.6V-Flash）。 */
enum class ImportEngine { LOCAL_OCR, AI_VISION }

@Stable
data class ImportReviewRow(
    val id: Long,
    val codeOrNameInput: String,
    val sharesInput: String,
    val costPerShareInput: String,
    val codeOrNameError: String?,
    val sharesError: String?,
    val costError: String?,
    val resolvedName: String? = null
)

@Stable
data class PortfolioImportUiState(
    val phase: ImportPhase = ImportPhase.Idle,
    val imageUri: String? = null,
    val rows: List<ImportReviewRow> = emptyList(),
    val ocrRawText: String? = null,
    val errorMessage: String? = null,
    val importSummary: String? = null,
    val engine: ImportEngine = ImportEngine.LOCAL_OCR,
    /** AI 引擎识别中的重试进度文案（「正在重试 n/5」），仅 Loading 阶段展示。 */
    val visionRetryStatus: String? = null
)

@HiltViewModel
class PortfolioImportViewModel @Inject constructor(
    private val marketDataPlane: MarketDataPlane,
    private val stockRepository: StockRepository,
    private val textRecognitionService: TextRecognitionService,
    private val visionImportRepository: VisionImportRepository,
    private val llmConfigRepository: LlmConfigRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortfolioImportUiState())
    val uiState: StateFlow<PortfolioImportUiState> = _uiState.asStateFlow()

    private var rowIdSeq = 0L

    /** 用户是否手动选过引擎（未选过时跟随视觉配置可用性设默认）。 */
    private var engineTouched = false

    init {
        viewModelScope.launch {
            llmConfigRepository.observeVisionConfig().collect { config ->
                val preferred = if (config.isComplete) ImportEngine.AI_VISION else ImportEngine.LOCAL_OCR
                if (!engineTouched && _uiState.value.engine != preferred) {
                    _uiState.update { it.copy(engine = preferred) }
                }
            }
        }
    }

    fun onEngineChanged(engine: ImportEngine) {
        engineTouched = true
        _uiState.update { it.copy(engine = engine) }
    }

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ImportPhase.LoadingImage,
                    imageUri = uri.toString(),
                    errorMessage = null,
                    ocrRawText = null,
                    rows = emptyList(),
                    importSummary = null,
                    visionRetryStatus = null
                )
            }
            try {
                if (_uiState.value.engine == ImportEngine.AI_VISION) {
                    parseWithVision()
                } else {
                    parseWithLocalOcr()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        phase = ImportPhase.Error,
                        errorMessage = "图片识别失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    /** 本地 ML Kit OCR + 坐标解析（离线，图片不上传）。 */
    private suspend fun parseWithLocalOcr() {
        _uiState.update { it.copy(phase = ImportPhase.OcrRunning) }
        val bitmap = loadSampledBitmap(context, Uri.parse(_uiState.value.imageUri))
        val elements = textRecognitionService.recognize(bitmap)
        val rawText = elements.joinToString("\n") { it.text }
        val parsed = HoldingScreenshotParser.parseFromElements(elements)
        if (parsed.isEmpty()) {
            _uiState.update {
                it.copy(
                    phase = ImportPhase.Error,
                    ocrRawText = rawText,
                    errorMessage = "未在截图中识别到持仓行，请确认截图来自持仓页，或手动添加行后导入。"
                )
            }
            return
        }
        val rows = parsed.map { p ->
            ImportReviewRow(
                id = rowIdSeq++,
                codeOrNameInput = p.codeOrName,
                sharesInput = p.shares?.toString() ?: "",
                costPerShareInput = p.costPerShare?.let { formatDouble(it) } ?: "",
                codeOrNameError = null,
                sharesError = null,
                costError = null
            )
        }
        _uiState.update {
            it.copy(
                phase = ImportPhase.Review,
                ocrRawText = rawText,
                rows = rows
            )
        }
    }

    /** AI 视觉模型解析（GLM-4.6V-Flash，图片压缩后上传智谱；失败自动重试 5 次）。 */
    private suspend fun parseWithVision() {
        _uiState.update { it.copy(phase = ImportPhase.OcrRunning) }
        val bitmap = loadSampledBitmap(context, Uri.parse(_uiState.value.imageUri))
        val result = visionImportRepository.parse(bitmap, VisionParseMode.HOLDINGS) { attempt, max, reason ->
            _uiState.update {
                it.copy(visionRetryStatus = "识别失败（$reason），正在自动重试 $attempt/$max…")
            }
        }
        when (result) {
            is VisionImportResult.Holdings -> {
                if (result.rows.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            phase = ImportPhase.Error,
                            errorMessage = "未在截图中识别到持仓行，请确认截图来自持仓页，或切换本地识别后重试。"
                        )
                    }
                    return
                }
                val rows = result.rows.map { p ->
                    ImportReviewRow(
                        id = rowIdSeq++,
                        codeOrNameInput = p.codeOrName,
                        sharesInput = p.shares?.toString() ?: "",
                        costPerShareInput = p.costPerShare?.let { formatDouble(it) } ?: "",
                        codeOrNameError = null,
                        sharesError = null,
                        costError = null,
                        resolvedName = p.name
                    )
                }
                _uiState.update { it.copy(phase = ImportPhase.Review, rows = rows) }
            }
            VisionImportResult.NotConfigured -> _uiState.update {
                it.copy(
                    phase = ImportPhase.Error,
                    errorMessage = "尚未配置视觉模型：请在「设置 → AI 与策略」填写视觉模型 API Key，或切回本地识别（不上传图片）。"
                )
            }
            is VisionImportResult.Error -> _uiState.update {
                it.copy(phase = ImportPhase.Error, errorMessage = result.message)
            }
            is VisionImportResult.Transactions -> _uiState.update {
                it.copy(phase = ImportPhase.Error, errorMessage = "识别到的是交易记录而非持仓，请改用「交易流水 → 截图导入交易记录」。")
            }
        }
    }

    fun onRowCodeOrNameChanged(id: Long, input: String) =
        updateRow(id) { it.copy(codeOrNameInput = input, codeOrNameError = null, resolvedName = null) }

    fun onRowSharesChanged(id: Long, input: String) =
        updateRow(id) { it.copy(sharesInput = input, sharesError = null) }

    fun onRowCostChanged(id: Long, input: String) =
        updateRow(id) { it.copy(costPerShareInput = input, costError = null) }

    fun removeRow(id: Long) {
        _uiState.update { state ->
            state.copy(rows = state.rows.filterNot { it.id == id })
        }
    }

    fun addEmptyRow() {
        _uiState.update { state ->
            state.copy(
                rows = state.rows + ImportReviewRow(
                    id = rowIdSeq++,
                    codeOrNameInput = "",
                    sharesInput = "",
                    costPerShareInput = "",
                    codeOrNameError = null,
                    sharesError = null,
                    costError = null
                )
            )
        }
    }

    fun resetToIdle() {
        _uiState.update {
            PortfolioImportUiState(phase = ImportPhase.Idle, engine = it.engine)
        }
    }

    /**
     * 校验所有行 + 解析股票 + 批量导入。校验失败的行标注 error 并中止导入。
     */
    fun confirmImport() {
        val currentRows = _uiState.value.rows
        if (currentRows.isEmpty()) return

        // Step 1: 本地校验每行字段
        val validated = currentRows.map { row -> validateRow(row) }
        val hasError = validated.any { it.codeOrNameError != null || it.sharesError != null || it.costError != null }
        if (hasError) {
            _uiState.update { it.copy(rows = validated) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(phase = ImportPhase.Importing, errorMessage = null) }
            try {
                val importRows = validated.mapNotNull { row ->
                    val shares = row.sharesInput.toIntOrNull() ?: return@mapNotNull null
                    val cost = row.costPerShareInput.toDoubleOrNull() ?: 0.0
                    ImportRow(row.codeOrNameInput.trim(), shares, cost)
                }
                val summary: ImportSummary = stockRepository.importHoldings(importRows)
                // 补充股息数据（导入流程未含，失败不影响导入结果）
                summary.succeeded.forEach { code ->
                    try {
                        marketDataPlane.refreshDividends(code)
                    } catch (_: Exception) { /* 股息缺失不影响导入 */ }
                }
                val msg = buildSummaryMessage(summary)
                _uiState.update {
                    it.copy(
                        phase = ImportPhase.Done,
                        importSummary = msg
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        phase = ImportPhase.Error,
                        errorMessage = "导入失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    private fun validateRow(row: ImportReviewRow): ImportReviewRow {
        val codeErr = if (row.codeOrNameInput.isBlank()) "请填写代码或名称" else null
        val shares = row.sharesInput.trim().toIntOrNull()
        val sharesErr = when {
            row.sharesInput.isBlank() -> "请填写股数"
            shares == null -> "股数需为整数"
            shares <= 0 -> "股数需大于 0"
            else -> null
        }
        val cost = row.costPerShareInput.trim().toDoubleOrNull()
        val costErr = when {
            row.costPerShareInput.isBlank() -> null // 成本价可选
            cost == null -> "成本价格式有误"
            cost < 0 -> "成本价不能为负"
            else -> null
        }
        return row.copy(
            codeOrNameError = codeErr,
            sharesError = sharesErr,
            costError = costErr
        )
    }

    private fun updateRow(id: Long, transform: (ImportReviewRow) -> ImportReviewRow) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun buildSummaryMessage(summary: ImportSummary): String {
        val ok = summary.succeeded.size
        val fail = summary.failed.size
        return buildString {
            append("成功导入 $ok 只")
            if (fail > 0) append("，失败 $fail 只（未匹配到股票，请核对代码/名称）")
        }
    }

    private fun formatDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
