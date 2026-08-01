package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownRenderGuardTest {

    @Test
    fun `普通文本可渲染`() {
        assertThat(canRenderMarkdown("你好")).isTrue()
    }

    @Test
    fun `成对代码围栏可渲染`() {
        assertThat(canRenderMarkdown("```kotlin\nval a = 1\n```\n后面还有内容")).isTrue()
    }

    @Test
    fun `未闭合代码围栏不可渲染`() {
        assertThat(canRenderMarkdown("```kotlin\nval a = 1")).isFalse()
    }

    @Test
    fun `空白文本可渲染`() {
        assertThat(canRenderMarkdown("")).isTrue()
    }
}
