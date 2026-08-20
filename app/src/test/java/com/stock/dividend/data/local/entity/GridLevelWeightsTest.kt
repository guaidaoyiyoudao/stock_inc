package com.stock.dividend.data.local.entity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GridLevelWeights]（grid_plans.levelWeights 列的 JSON 编解码）单测。
 *
 * 语义：weights 为「各档相对资金权重」，与档位列表同序（从最便宜档起）；
 * 持久化为 JSON 数组字符串（如 "[20.0,30.0,50.0]"），null = 反比默认分配。
 */
class GridLevelWeightsTest {

    @Test
    fun `toJson then parse round trips`() {
        val raw = GridLevelWeights.toJson(listOf(1.0, 3.0, 6.0))
        assertThat(GridLevelWeights.parse(raw)).containsExactly(1.0, 3.0, 6.0).inOrder()
    }

    @Test
    fun `parse accepts integer entries and spaces`() {
        assertThat(GridLevelWeights.parse("[20, 30, 50]"))
            .containsExactly(20.0, 30.0, 50.0).inOrder()
    }

    @Test
    fun `parse null blank or malformed returns null`() {
        assertThat(GridLevelWeights.parse(null)).isNull()
        assertThat(GridLevelWeights.parse("")).isNull()
        assertThat(GridLevelWeights.parse("   ")).isNull()
        assertThat(GridLevelWeights.parse("abc")).isNull()
        assertThat(GridLevelWeights.parse("[1,2")).isNull()      // 缺右括号
        assertThat(GridLevelWeights.parse("1,2,3")).isNull()     // 缺括号
        assertThat(GridLevelWeights.parse("[1,,2]")).isNull()    // 空条目
        assertThat(GridLevelWeights.parse("[1,a,2]")).isNull()   // 非数字
    }

    @Test
    fun `parse empty array or nonpositive entries returns null`() {
        assertThat(GridLevelWeights.parse("[]")).isNull()
        assertThat(GridLevelWeights.parse("[0,1]")).isNull()     // 0 权重非法
        assertThat(GridLevelWeights.parse("[1,-2]")).isNull()    // 负权重非法
    }

    @Test
    fun `toJson omits trailing zeros noise`() {
        // 权重是用户输入的相对比例（如 20/30/50），序列化保持数值本身
        assertThat(GridLevelWeights.toJson(listOf(20.0, 30.0)))
            .isEqualTo("[20.0,30.0]")
    }
}
