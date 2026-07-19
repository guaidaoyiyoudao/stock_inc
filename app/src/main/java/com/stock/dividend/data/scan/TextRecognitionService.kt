package com.stock.dividend.data.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR 抽象：把 Bitmap 识别为整段文本。便于替换实现与单测 mock。
 */
interface TextRecognitionService {
    suspend fun recognize(bitmap: Bitmap): String
}

/**
 * 基于 ML Kit 中文文本识别（离线、免权限）。
 * 识别器在构造时一次性创建，复用 Task API。Scope 由 [com.stock.dividend.di.OcrModule] 的 @Singleton 控制。
 */
class MlKitTextRecognitionService @Inject constructor() : TextRecognitionService {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
