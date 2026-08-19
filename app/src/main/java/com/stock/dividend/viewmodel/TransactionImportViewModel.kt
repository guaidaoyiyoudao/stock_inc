package com.stock.dividend.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.repository.LlmConfigRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionImportRow
import com.stock.dividend.data.repository.TransactionImportSummary
import com.stock.dividend.data.repository.VisionImportRepository
import com.stock.dividend.data.repository.VisionImportResult
import com.stock.dividend.data.repository.VisionParseMode
import com.stock.dividend.data.scan.loadSampledBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject

enum class TransactionImportPhase { Idle, LoadingImage, Analyzing, Review, Importing, Done, Error }

@Stable
data class TransactionReviewRow(
    val id: Long,
    val codeOrNameInput: String,
    val typeInput: String, // "BUY" / "SELL"
    val sharesInput: String,
    val priceInput: String,
    val dateInput: String, // yyyy-MM-dd
    val codeOrNameError: String?,
    val typeError: String?,
    val sharesError: String?,
    val priceError: String?,
    val dateError: String?,
    val resolvedName: String? = null
)

@Stable
data class TransactionImportUiState(
    val phase: TransactionImportPhase = TransactionImportPhase.Idle,
    val imageUri: String? = null,
    val rows: List<TransactionReviewRow> = emptyList(),
    val errorMessage: String? = null,
    val importSummary: String? = null,
    /** AI 识别中的重试进度文案（「正在重试 n/5」），仅识别阶段展示。 */
    val visionRetryStatus: String? = null,
    /** 视觉模型是否已配置（Idle 页据此显示引导）。 */
    val visionConfigured: Boolean = false
)

/**
 * 交易记录截图导入 VM：AI 视觉解析（GLM-4.6V）→ 行级可编辑核对 → [StockRepository.importTransactions]。
 * 交易表格（日期/方向/数量/价格）依赖视觉模型，不提供本地 OCR 路径；未配置时手动加行仍可导入。
 */
@HiltViewModel
class TransactionImportViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val visionImportRepository: VisionImportRepository,
    private val llmConfigRepository: LlmConfigRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionImportUiState())
    val uiState: StateFlow<TransactionImportUiState> = _uiState.asStateFlow()

    private var rowIdSeq = 0L

    init {
        viewModelScope.launch {
            llmConfigRepository.observeVisionConfig().collect { config ->
                _uiState.update { it.copy(visionConfigured = config.isComplete) }
            }
        }
    }

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = TransactionImportPhase.LoadingImage,
                    imageUri = uri.toString(),
                    errorMessage = null,
                    rows = emptyList(),
                    importSummary = null,
                    visionRetryStatus = null
                )
            }
            try {
                _uiState.update { it.copy(phase = TransactionImportPhase.Analyzing) }
                val bitmap = loadSampledBitmap(context, uri)
                val result = visionImportRepository.parse(bitmap, VisionParseMode.TRANSACTIONS) { attempt, max, reason ->
                    _uiState.update {
                        it.copy(visionRetryStatus = "识别失败（$reason），正在自动重试 $attempt/$max…")
                    }
                }
                when (result) {
                    is VisionImportResult.Transactions -> {
                        if (result.rows.isEmpty()) {
                            _uiState.update {
                                it.copy(
                                    phase = TransactionImportPhase.Error,
                                    errorMessage = "未在截图中识别到成交记录，请确认截图来自「历史成交/交易记录」页，或手动添加行后导入。"
                                )
                            }
                            return@launch
                        }
                        val rows = result.rows.map { p ->
                            TransactionReviewRow(
                                id = rowIdSeq++,
                                codeOrNameInput = p.codeOrName,
                                typeInput = p.type ?: "BUY",
                                sharesInput = p.shares?.toString() ?: "",
                                priceInput = p.price?.let { formatDouble(it) } ?: "",
                                dateInput = p.date ?: "",
                                codeOrNameError = null,
                                typeError = null,
                                sharesError = null,
                                priceError = null,
                                dateError = null,
                                resolvedName = p.name
                            )
                        }
                        _uiState.update { it.copy(phase = TransactionImportPhase.Review, rows = rows) }
                    }
                    is VisionImportResult.Holdings -> _uiState.update {
                        it.copy(
                            phase = TransactionImportPhase.Error,
                            errorMessage = "识别到的是持仓页而非成交记录，请改用「持仓页 → 从截图导入持仓」。"
                        )
                    }
                    VisionImportResult.NotConfigured -> _uiState.update {
                        it.copy(
                            phase = TransactionImportPhase.Error,
                            errorMessage = "尚未配置视觉模型：请在「设置 → AI 与策略」填写视觉模型 API Key（GLM-4.6V-Flash 免费），或手动添加行后导入。"
                        )
                    }
                    is VisionImportResult.Error -> _uiState.update {
                        it.copy(phase = TransactionImportPhase.Error, errorMessage = result.message)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        phase = TransactionImportPhase.Error,
                        errorMessage = "图片识别失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun onRowCodeOrNameChanged(id: Long, input: String) =
        updateRow(id) { it.copy(codeOrNameInput = input, codeOrNameError = null, resolvedName = null) }

    fun onRowTypeChanged(id: Long, type: String) =
        updateRow(id) { it.copy(typeInput = type, typeError = null) }

    fun onRowSharesChanged(id: Long, input: String) =
        updateRow(id) { it.copy(sharesInput = input, sharesError = null) }

    fun onRowPriceChanged(id: Long, input: String) =
        updateRow(id) { it.copy(priceInput = input, priceError = null) }

    fun onRowDateChanged(id: Long, input: String) =
        updateRow(id) { it.copy(dateInput = input, dateError = null) }

    fun removeRow(id: Long) {
        _uiState.update { state -> state.copy(rows = state.rows.filterNot { it.id == id }) }
    }

    fun addEmptyRow() {
        _uiState.update { state ->
            state.copy(
                rows = state.rows + TransactionReviewRow(
                    id = rowIdSeq++,
                    codeOrNameInput = "",
                    typeInput = "BUY",
                    sharesInput = "",
                    priceInput = "",
                    dateInput = LocalDate.now().toString(),
                    codeOrNameError = null,
                    typeError = null,
                    sharesError = null,
                    priceError = null,
                    dateError = null
                )
            )
        }
    }

    fun resetToIdle() {
        _uiState.update { TransactionImportUiState(phase = TransactionImportPhase.Idle, visionConfigured = it.visionConfigured) }
    }

    /** 校验所有行 + 批量导入。校验失败的行标注 error 并中止导入。 */
    fun confirmImport() {
        val currentRows = _uiState.value.rows
        if (currentRows.isEmpty()) return

        val validated = currentRows.map { row -> validateRow(row) }
        val hasError = validated.any {
            it.codeOrNameError != null || it.typeError != null || it.sharesError != null ||
                it.priceError != null || it.dateError != null
        }
        if (hasError) {
            _uiState.update { it.copy(rows = validated) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(phase = TransactionImportPhase.Importing, errorMessage = null) }
            try {
                val importRows = validated.map { row ->
                    TransactionImportRow(
                        rawCodeOrName = row.codeOrNameInput.trim(),
                        type = row.typeInput,
                        shares = row.sharesInput.trim().toIntOrNull() ?: 0,
                        price = row.priceInput.trim().toDoubleOrNull() ?: 0.0,
                        date = row.dateInput.trim()
                    )
                }
                val summary: TransactionImportSummary = stockRepository.importTransactions(importRows)
                _uiState.update {
                    it.copy(phase = TransactionImportPhase.Done, importSummary = buildSummaryMessage(summary))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        phase = TransactionImportPhase.Error,
                        errorMessage = "导入失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    private fun validateRow(row: TransactionReviewRow): TransactionReviewRow {
        val codeErr = if (row.codeOrNameInput.isBlank()) "请填写代码或名称" else null
        val typeErr = if (row.typeInput != "BUY" && row.typeInput != "SELL") "请选择买/卖" else null
        val shares = row.sharesInput.trim().toIntOrNull()
        val sharesErr = when {
            row.sharesInput.isBlank() -> "请填写股数"
            shares == null -> "股数需为整数"
            shares <= 0 -> "股数需大于 0"
            else -> null
        }
        val price = row.priceInput.trim().toDoubleOrNull()
        val priceErr = when {
            row.priceInput.isBlank() -> "请填写成交价"
            price == null -> "成交价格式有误"
            price <= 0 -> "成交价需大于 0"
            else -> null
        }
        val dateErr = when {
            row.dateInput.isBlank() -> "请填写日期"
            else -> try {
                LocalDate.parse(row.dateInput.trim())
                null
            } catch (_: DateTimeParseException) {
                "日期需为 yyyy-MM-dd"
            }
        }
        return row.copy(
            codeOrNameError = codeErr,
            typeError = typeErr,
            sharesError = sharesErr,
            priceError = priceErr,
            dateError = dateErr
        )
    }

    private fun updateRow(id: Long, transform: (TransactionReviewRow) -> TransactionReviewRow) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun buildSummaryMessage(summary: TransactionImportSummary): String = buildString {
        append("成功导入 ${summary.insertedCount} 笔交易")
        if (summary.duplicatesSkipped > 0) append("，跳过重复 ${summary.duplicatesSkipped} 笔")
        if (summary.failedRows.isNotEmpty()) {
            append("，失败 ${summary.failedRows.size} 笔（未匹配到股票，请核对代码/名称）")
        }
    }

    private fun formatDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
