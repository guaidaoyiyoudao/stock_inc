package com.stock.dividend.viewmodel

/**
 * Markdown 可渲染性守卫：只有语法完整可解析的文本才交给 Markdown 渲染器。
 * 目前校验：代码围栏必须成对闭合（未闭合的 ``` 会吞掉后续所有内容）。
 * 纯函数，便于单测。
 */
internal fun canRenderMarkdown(text: String): Boolean {
    if (text.isBlank()) return true
    val fenceCount = text.lineSequence()
        .count { it.trimStart().startsWith("```") }
    return fenceCount % 2 == 0
}
