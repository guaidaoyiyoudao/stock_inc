package com.stock.dividend.data.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/** bitmapToJpegDataUrl：data URL 前缀 + 超边缩放（Robolectric NATIVE 图形模式下真实编码）。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapLoaderTest {

    /** 纯色位图会被 JPEG 压得极小，先铺随机色块让编码体积有区分度。 */
    private fun noisyBitmap(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val random = Random(42)
        repeat(400) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val x = random.nextInt(w - 20)
            val y = random.nextInt(h - 20)
            canvas.drawRect(
                x.toFloat(), y.toFloat(), (x + 20).toFloat(), (y + 20).toFloat(), paint
            )
        }
        return bitmap
    }

    @Test
    fun `produces jpeg data url for small bitmap`() {
        val bitmap = noisyBitmap(800, 600)

        val dataUrl = bitmapToJpegDataUrl(bitmap, maxEdge = 1600, quality = 80)

        assertThat(dataUrl).startsWith("data:image/jpeg;base64,")
        assertThat(dataUrl.length).isGreaterThan("data:image/jpeg;base64,".length + 100)
        assertThat(dataUrl).doesNotContain("\n")
    }

    @Test
    fun `scales bitmap exceeding max edge before encoding`() {
        val bitmap = noisyBitmap(3200, 1600)

        val dataUrl = bitmapToJpegDataUrl(bitmap, maxEdge = 1600, quality = 80)
        val fullSize = bitmapToJpegDataUrl(bitmap, maxEdge = 4096, quality = 80)

        // 3200→1600 缩放后编码体积应明显小于全尺寸
        assertThat(dataUrl).startsWith("data:image/jpeg;base64,")
        assertThat(dataUrl.length).isLessThan(fullSize.length)
    }
}
