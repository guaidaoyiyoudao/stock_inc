package com.stock.dividend.data.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR 识别出的单个文本元素，保留屏幕坐标（单位 px，原点为图片左上角）。
 * 用于按视觉位置聚类成表格行/列，而非依赖固定文本格式。
 */
data class OcrElement(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * OCR 抽象：把 Bitmap 识别为带坐标的元素列表。便于替换实现与单测 mock。
 */
interface TextRecognitionService {
    /** 返回所有识别出的最小文本块及其坐标。 */
    suspend fun recognize(bitmap: Bitmap): List<OcrElement>
}

/**
 * 基于 ML Kit 中文文本识别（走 Play Services，模型按需下载）。
 * 返回每个 [Text.Element]（最细粒度的文本单元）+ boundingBox。
 */
@Singleton
class MlKitTextRecognitionService @Inject constructor() : TextRecognitionService {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun recognize(bitmap: Bitmap): List<OcrElement> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(toElements(result)) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    /**
     * ML Kit 的层级是 Block → Line → Element。Element 太碎（会把"贵州茅台"切成多个），
     * Line 又可能把同行多列合并。这里折中取 Block，再对过宽的 Block 按 Line 拆分。
     */
    private fun toElements(result: Text): List<OcrElement> {
        val out = mutableListOf<OcrElement>()
        for (block in result.textBlocks) {
            val blockBox = block.boundingBox ?: continue
            for (line in block.lines) {
                val lineBox = line.boundingBox ?: continue
                // 把每个 Line 当作一个视觉元素（同行内的多个 token 在一条 Line 里）。
                // ML Kit 的 Line 通常代表视觉上的一行文字，正好符合"按行聚类"的需求。
                val text = line.text.trim()
                if (text.isNotEmpty()) {
                    out.add(
                        OcrElement(
                            text = text,
                            left = lineBox.left.toFloat(),
                            top = lineBox.top.toFloat(),
                            right = lineBox.right.toFloat(),
                            bottom = lineBox.bottom.toFloat()
                        )
                    )
                }
            }
        }
        return out
    }
}
