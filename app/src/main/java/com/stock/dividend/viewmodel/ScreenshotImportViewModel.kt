package com.stock.dividend.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.STRATEGY_STATUS_ACTIVE
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.repository.ScreenshotStrategy
import com.stock.dividend.data.repository.ScreenshotStrategyRepository
import com.stock.dividend.data.repository.ScreenshotStrategyState
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.risksToJsonString
import com.stock.dividend.data.scan.TextRecognitionService
import com.stock.dividend.data.scan.loadSampledBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ScreenshotImportPhase {
    Idle, LoadingImage, OcrRunning, ReviewOcr, Analyzing, ReviewStrategy, Done, Error
}

@Stable
data class EditableStrategy(
    val targetText: String,
    val direction: ScreenshotStrategy.StrategyDirection,
    val reasoning: String,
    val risks: MutableList<String>,
    val validUntil: String?
)

@Stable
data class ScreenshotImportUiState(
    val phase: ScreenshotImportPhase = ScreenshotImportPhase.Idle,
    val imageUri: String? = null,
    val editableOcrText: String = "",
    val analysisError: String? = null,
    val editableStrategy: EditableStrategy? = null,
    val sourceNote: String = "",
    val errorMessage: String? = null
)

/**
 * 截图策略分析导入页 VM：两步 Review（OCR 文本可编辑 → 策略字段可编辑）。
 * 策略全局，不关联个股，故不注入 StockRepository。
 */
@HiltViewModel
class ScreenshotImportViewModel @Inject constructor(
    private val textRecognitionService: TextRecognitionService,
    private val screenshotStrategyRepository: ScreenshotStrategyRepository,
    private val strategyRepository: TradeStrategyRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScreenshotImportUiState())
    val uiState: StateFlow<ScreenshotImportUiState> = _uiState.asStateFlow()

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ScreenshotImportPhase.LoadingImage,
                    imageUri = uri.toString(),
                    errorMessage = null,
                    analysisError = null
                )
            }
            try {
                _uiState.update { it.copy(phase = ScreenshotImportPhase.OcrRunning) }
                val bitmap = loadSampledBitmap(context, uri)
                val elements = textRecognitionService.recognize(bitmap)
                val text = elements.joinToString("\n") { it.text }
                if (text.isBlank()) {
                    _uiState.update {
                        it.copy(phase = ScreenshotImportPhase.Error, errorMessage = "未识别到文本")
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(phase = ScreenshotImportPhase.ReviewOcr, editableOcrText = text)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        phase = ScreenshotImportPhase.Error,
                        errorMessage = "图片识别失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun onOcrTextChanged(t: String) {
        if (_uiState.value.phase == ScreenshotImportPhase.ReviewOcr) {
            _uiState.update { it.copy(editableOcrText = t) }
        }
    }

    fun startAnalysis() {
        if (_uiState.value.phase != ScreenshotImportPhase.ReviewOcr) return
        val ocrText = _uiState.value.editableOcrText
        _uiState.update { it.copy(phase = ScreenshotImportPhase.Analyzing, analysisError = null) }
        viewModelScope.launch {
            when (val r = screenshotStrategyRepository.analyze(ocrText)) {
                is ScreenshotStrategyState.Success -> {
                    val s = r.strategy
                    _uiState.update {
                        it.copy(
                            phase = ScreenshotImportPhase.ReviewStrategy,
                            editableStrategy = EditableStrategy(
                                s.targetText, s.direction, s.reasoning,
                                s.risks.toMutableList(), s.validUntil
                            ),
                            analysisError = null
                        )
                    }
                }
                is ScreenshotStrategyState.NoStrategy -> _uiState.update {
                    it.copy(phase = ScreenshotImportPhase.ReviewOcr, analysisError = r.message)
                }
                is ScreenshotStrategyState.Error -> _uiState.update {
                    it.copy(phase = ScreenshotImportPhase.ReviewOcr, analysisError = r.message)
                }
                is ScreenshotStrategyState.NotConfigured -> _uiState.update {
                    it.copy(phase = ScreenshotImportPhase.ReviewOcr, analysisError = "需先在设置配置 LLM")
                }
                ScreenshotStrategyState.Idle, ScreenshotStrategyState.Loading -> Unit
            }
        }
    }

    // 第二步编辑方法（用 copy 返回新对象，避免突变 risks）
    fun onTargetTextChanged(t: String) = editStrategy { it.copy(targetText = t) }
    fun onDirectionChanged(d: ScreenshotStrategy.StrategyDirection) = editStrategy { it.copy(direction = d) }
    fun onReasoningChanged(t: String) = editStrategy { it.copy(reasoning = t) }
    fun onRiskChanged(i: Int, t: String) = editStrategy { es ->
        es.copy(risks = es.risks.toMutableList().also { it[i] = t })
    }
    fun addRisk() = editStrategy { es ->
        es.copy(risks = es.risks.toMutableList().apply { add("") })
    }
    fun removeRisk(i: Int) = editStrategy { es ->
        es.copy(risks = es.risks.toMutableList().apply { removeAt(i) })
    }
    fun onValidUntilChanged(d: String?) = editStrategy { it.copy(validUntil = d) }
    fun onSourceNoteChanged(t: String) = _uiState.update { it.copy(sourceNote = t) }

    private fun editStrategy(transform: (EditableStrategy) -> EditableStrategy) {
        _uiState.update { st ->
            val cur = st.editableStrategy ?: return@update st
            st.copy(editableStrategy = transform(cur))
        }
    }

    fun backToOcrReview() {
        _uiState.update {
            it.copy(phase = ScreenshotImportPhase.ReviewOcr, editableStrategy = null, analysisError = null)
        }
    }

    fun confirmSave() {
        val cur = _uiState.value.editableStrategy ?: return
        viewModelScope.launch {
            try {
                val entity = TradeStrategyEntity(
                    id = UUID.randomUUID().toString(),
                    targetText = cur.targetText,
                    direction = cur.direction.name,
                    reasoning = cur.reasoning,
                    risks = risksToJsonString(cur.risks.filter { it.isNotBlank() }),
                    validUntil = cur.validUntil?.takeIf { it.isNotBlank() },
                    sourceNote = _uiState.value.sourceNote.takeIf { it.isNotBlank() },
                    rawOcrText = _uiState.value.editableOcrText,
                    status = STRATEGY_STATUS_ACTIVE
                )
                strategyRepository.upsert(entity)
                _uiState.update { it.copy(phase = ScreenshotImportPhase.Done, errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "保存失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun resetToIdle() {
        _uiState.value = ScreenshotImportUiState()
    }
}
