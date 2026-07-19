package com.stock.dividend.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
