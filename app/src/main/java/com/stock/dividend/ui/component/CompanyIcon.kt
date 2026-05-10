package com.stock.dividend.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

private val logoUrlByCode = mapOf(
    "BABA" to "https://svglogo.top/library/alibaba.svg",
    "TCEHY" to "https://svglogo.top/library/tencent.svg",
    "0700" to "https://svglogo.top/library/tencent.svg",
    "PDD" to "https://svglogo.top/library/pinduoduo.svg",
    "JD" to "https://svglogo.top/library/jingdong.svg",
    "BIDU" to "https://svglogo.top/library/baidu.svg",
    "NTES" to "https://svglogo.top/library/wangyiyun.svg",
    "BILI" to "https://svglogo.top/library/bilibili.svg",
    "XIAOMI" to "https://svglogo.top/library/xiaomi.svg",
    "1810" to "https://svglogo.top/library/xiaomi.svg"
)

@Composable
fun CompanyIcon(
    stockCode: String,
    stockName: String,
    modifier: Modifier = Modifier,
    size: Int = 44
) {
    val normalizedCode = stockCode.substringAfterLast('.').uppercase()
    val logoUrl = logoUrlByCode[normalizedCode]
    val fallbackLabel = stockName.trim().take(1).ifEmpty { normalizedCode.take(1).ifEmpty { "?" } }
    val context = LocalPlatformContext.current

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (logoUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(logoUrl)
                    .decoderFactory(SvgDecoder.Factory())
                    .memoryCacheKey("company-logo-$normalizedCode")
                    .diskCacheKey("company-logo-$normalizedCode")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "$stockName logo",
                modifier = Modifier.size((size * 0.75f).dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = fallbackLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
