package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownRenderGuardTest {

    @Test
    fun plainText_isRenderable() {
        assertThat(canRenderMarkdown("你好")).isTrue()
    }

    @Test
    fun pairedCodeFence_isRenderable() {
        assertThat(canRenderMarkdown("```kotlin\nval a = 1\n```\n后面还有内容")).isTrue()
    }

    @Test
    fun unclosedCodeFence_isNotRenderable() {
        assertThat(canRenderMarkdown("```kotlin\nval a = 1")).isFalse()
    }

    @Test
    fun blankText_isRenderable() {
        assertThat(canRenderMarkdown("")).isTrue()
    }
}
