package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema

/** 只读工具基类：手写 declaration，免 KSP。 */
abstract class ReadTool(
    name: String,
    description: String,
    private val parameters: Schema? = null,
) : BaseTool(name, description) {
    final override fun declaration(): FunctionDeclaration =
        FunctionDeclaration(name = name, description = description, parameters = parameters)
}

/** 写操作工具基类：ADK 内置确认门（requiresConfirmation=true）+ 手写 declaration。 */
abstract class WriteTool(
    name: String,
    description: String,
    private val parameters: Schema? = null,
) : FunctionTool(name, description, requiresConfirmation = true) {
    final override fun declaration(): FunctionDeclaration =
        FunctionDeclaration(name = name, description = description, parameters = parameters)
}
