package com.stock.dividend.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.ImportRow
import com.stock.dividend.data.repository.ImportSummary
import com.stock.dividend.data.repository.StockRepository
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
    val importSummary: String? = null
)

@HiltViewModel
class PortfolioImportViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val textRecognitionService: TextRecognitionService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortfolioImportUiState())
    val uiState: StateFlow<PortfolioImportUiState> = _uiState.asStateFlow()

    private var rowIdSeq = 0L

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ImportPhase.LoadingImage,
                    imageUri = uri.toString(),
                    errorMessage = null,
                    ocrRawText = null,
                    rows = emptyList(),
                    importSummary = null
                )
            }
            try {
                _uiState.update { it.copy(phase = ImportPhase.OcrRunning) }
                val bitmap = loadSampledBitmap(context, uri)
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
                    return@launch
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
            PortfolioImportUiState(phase = ImportPhase.Idle)
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
                        dividendRepository.fetchAndCacheDividends(code, code.substringAfter("."))
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
