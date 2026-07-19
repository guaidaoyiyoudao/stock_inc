package com.stock.dividend.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldingScreenshotParserTest {

    private fun el(text: String, x: Float, y: Float, w: Float = 80f, h: Float = 40f) =
        OcrElement(text, x, y, x + w, y + h)

    /**
     * 模拟同花顺分块布局：
     * - 名称在 X=50 列，Y 各不同（每只股票一行）
     * - 股数在 X=400 列，Y 与对应名称对齐
     * - 成本价在 X=600 列，Y 与对应名称对齐
     */
    @Test
    fun `parses block layout by x-column clustering`() {
        val elements = listOf(
            el("贵州茅台", x = 50f, y = 100f),
            el("6500", x = 400f, y = 100f),
            el("1500.00", x = 600f, y = 100f),

            el("平安银行", x = 50f, y = 160f),
            el("1000", x = 400f, y = 160f),
            el("12.34", x = 600f, y = 160f),

            el("海澜之家", x = 50f, y = 220f),
            el("200", x = 400f, y = 220f),
            el("9.80", x = 600f, y = 220f)
        )

        val rows = HoldingScreenshotParser.parseFromElements(elements)

        assertThat(rows).hasSize(3)
        assertThat(rows[0].codeOrName).isEqualTo("贵州茅台")
        assertThat(rows[0].shares).isEqualTo(6500)
        assertThat(rows[0].costPerShare).isWithin(0.01).of(1500.0)
        assertThat(rows[1].codeOrName).isEqualTo("平安银行")
        assertThat(rows[1].shares).isEqualTo(1000)
        assertThat(rows[2].codeOrName).isEqualTo("海澜之家")
    }

    /** 列顺序无关：股数在左、名称在右也应正确解析。 */
    @Test
    fun `handles reversed column order`() {
        val elements = listOf(
            el("6500", x = 50f, y = 100f),
            el("贵州茅台", x = 400f, y = 100f),
            el("1500.00", x = 600f, y = 100f),

            el("1000", x = 50f, y = 160f),
            el("平安银行", x = 400f, y = 160f),
            el("12.34", x = 600f, y = 160f)
        )

        val rows = HoldingScreenshotParser.parseFromElements(elements)

        assertThat(rows).hasSize(2)
        assertThat(rows[0].codeOrName).isEqualTo("贵州茅台")
        assertThat(rows[0].shares).isEqualTo(6500)
        assertThat(rows[0].costPerShare).isWithin(0.01).of(1500.0)
    }

    /** 缺价格列（只截了名称和股数）也应能解析，价格返回 null。 */
    @Test
    fun `parses when price column is absent`() {
        val elements = listOf(
            el("贵州茅台", x = 50f, y = 100f),
            el("6500", x = 400f, y = 100f),
            el("平安银行", x = 50f, y = 160f),
            el("1000", x = 400f, y = 160f)
        )

        val rows = HoldingScreenshotParser.parseFromElements(elements)

        assertThat(rows).hasSize(2)
        assertThat(rows[0].shares).isEqualTo(6500)
        assertThat(rows[0].costPerShare).isNull()
    }

    /** 表头和占比列应被忽略。 */
    @Test
    fun `ignores headers and percent column`() {
        val elements = listOf(
            el("证券名称", x = 50f, y = 40f),
            el("持股数", x = 400f, y = 40f),
            el("成本价", x = 600f, y = 40f),

            el("贵州茅台", x = 50f, y = 100f),
            el("6500", x = 400f, y = 100f),
            el("1500.00", x = 600f, y = 100f),
            el("5.1%", x = 800f, y = 100f),

            el("平安银行", x = 50f, y = 160f),
            el("1000", x = 400f, y = 160f),
            el("12.34", x = 600f, y = 160f),
            el("7.3%", x = 800f, y = 160f)
        )

        val rows = HoldingScreenshotParser.parseFromElements(elements)

        assertThat(rows).hasSize(2)
        assertThat(rows[0].codeOrName).isEqualTo("贵州茅台")
        assertThat(rows[1].codeOrName).isEqualTo("平安银行")
    }

    /** 千分位股数应被正确解析。 */
    @Test
    fun `parses thousand-separated shares`() {
        val elements = listOf(
            el("贵州茅台", x = 50f, y = 100f),
            el("6,500", x = 400f, y = 100f),
            el("平安银行", x = 50f, y = 160f),
            el("1,000", x = 400f, y = 160f)
        )

        val rows = HoldingScreenshotParser.parseFromElements(elements)

        assertThat(rows[0].shares).isEqualTo(6500)
        assertThat(rows[1].shares).isEqualTo(1000)
    }

    /** 含 XD/ST 前缀的名称应保留。 */
    @Test
    fun `keeps XD prefix in name`() {
        val elements = listOf(
            el("XD中国核电", x = 50f, y = 100f),
            el("1800", x = 400f, y = 100f)
        )

        val rows = HoldingScreenshotParser.parseFromElements(elements)

        assertThat(rows).hasSize(1)
        assertThat(rows[0].codeOrName).isEqualTo("XD中国核电")
        assertThat(rows[0].shares).isEqualTo(1800)
    }

    /** 单行含完整记录（名称+股数+价格挤在同一视觉行）也应能解析。 */
    @Test
    fun `parses single-line records`() {
        val elements = listOf(
            el("贵州茅台 600519 100 1500.00", x = 50f, y = 100f, w = 600f),
            el("平安银行 000001 1000 12.34", x = 50f, y = 160f, w = 600f)
        )

        val rows = HoldingScreenshotParser.parseFromElements(elements)

        // 单行模式：名称和数字在一个 element 里，按行配对
        assertThat(rows.size).isAtLeast(1)
    }

    @Test
    fun `empty elements returns empty`() {
        assertThat(HoldingScreenshotParser.parseFromElements(emptyList())).isEmpty()
    }

    // ---------- 文本兼容入口（旧调用方/简单场景） ----------

    @Test
    fun `text mode parse handles single-line records`() {
        val text = """
            贵州茅台 100 1500.00
            平安银行 1000 12.34
        """.trimIndent()
        val rows = HoldingScreenshotParser.parse(text)
        assertThat(rows.size).isAtLeast(1)
        assertThat(rows.first().codeOrName).isEqualTo("贵州茅台")
    }

    @Test
    fun `text mode blank returns empty`() {
        assertThat(HoldingScreenshotParser.parse("")).isEmpty()
    }
}
