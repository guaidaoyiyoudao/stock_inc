package com.stock.dividend.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 从 content Uri 解码为 Bitmap，自动按目标尺寸下采样以防止 OOM。
 *
 * 纯 BitmapFactory 实现（无 Coil 类型耦合），用两步法：先测尺寸再解码。
 */
suspend fun loadSampledBitmap(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver

    // Step 1: 仅解析边界，不加载像素
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }

    val targetMax = 2048
    val sample = calcInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, targetMax)

    // Step 2: 用 sampleSize 真正解码
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: throw IllegalStateException("无法解码所选图片")
}

private fun calcInSampleSize(width: Int, height: Int, targetMax: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while ((width / sample) > targetMax || (height / sample) > targetMax) {
        sample *= 2
    }
    return sample
}

/**
 * Bitmap → JPEG data URL（base64），作为视觉模型 `image_url` 入参。
 *
 * [loadSampledBitmap] 是 2 的幂粗采样（最长边 ≤2048），这里再按 [maxEdge] 精确缩放，
 * 配合 JPEG [quality] 压缩控制请求体大小（1600px/80% 单张约 150-400KB）。
 */
fun bitmapToJpegDataUrl(
    bitmap: Bitmap,
    maxEdge: Int = 1600,
    quality: Int = 80
): String {
    val scaled = scaleToFit(bitmap, maxEdge)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    // scaleToFit 新建的缩放位图用完即回收（未超边时返回原 bitmap，不回收调用方的）
    if (scaled !== bitmap) scaled.recycle()
    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    return "data:image/jpeg;base64,$base64"
}

private fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxEdge) return bitmap
    val scale = maxEdge.toFloat() / longest
    val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
}
