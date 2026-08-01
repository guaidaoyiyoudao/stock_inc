package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.repository.FireGoalRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import kotlinx.coroutines.flow.first

private val PERIODS = setOf(EXPENSE_PERIOD_MONTHLY, EXPENSE_PERIOD_YEARLY)

class GetLivingExpensesTool(
    private val livingExpenseRepository: LivingExpenseRepository,
) : ReadTool(
    name = "get_living_expenses",
    description = "查询全部生活支出（含 id、名称、金额、周期）。修改/删除支出前必须先调用本工具获取 id。无需参数。",
) {
    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = runCatching {
        val expenses = livingExpenseRepository.observeExpenses().first()
        mapOf(
            "expenses" to expenses.map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "amount" to it.amount,
                    "period" to it.period
                )
            }
        )
    }.getOrElse { e -> mapOf("error" to (e.message ?: "查询失败")) }
}

class AddLivingExpenseTool(
    private val livingExpenseRepository: LivingExpenseRepository,
) : WriteTool(
    name = "add_living_expense",
    description = "添加一条生活支出（用于 FIRE 覆盖率计算）。amount 为金额（元，>0），如 3000；period 必须为 MONTHLY（月）或 YEARLY（年），缺省 MONTHLY。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "name" to Schema(type = Type.STRING, description = "支出名称，如「房租」"),
            "amount" to Schema(type = Type.NUMBER, description = "金额（元），必须大于 0，如 3000"),
            "period" to Schema(type = Type.STRING, description = "周期，必须为 MONTHLY 或 YEARLY，默认 MONTHLY")
        ),
        required = listOf("name", "amount")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val name = args.stringArg("name") ?: return mapOf("error" to "缺少 name 参数")
        val amount = args.doubleArg("amount") ?: return mapOf("error" to "缺少 amount 参数")
        val period = args.stringArg("period") ?: EXPENSE_PERIOD_MONTHLY
        if (amount <= 0) return mapOf("error" to "金额必须大于 0")
        if (period !in PERIODS) return mapOf("error" to "period 只能是 MONTHLY 或 YEARLY")
        return runCatching {
            val id = livingExpenseRepository.addExpense(name, amount, period)
            mapOf("ok" to true, "id" to id, "name" to name, "amount" to amount, "period" to period)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "添加失败")) }
    }
}

class UpdateLivingExpenseTool(
    private val livingExpenseRepository: LivingExpenseRepository,
) : WriteTool(
    name = "update_living_expense",
    description = "修改一条生活支出的名称/金额/周期。id 必须先调用 get_living_expenses 获取；其余参数同 add_living_expense。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "id" to Schema(type = Type.INTEGER, description = "支出记录 id（数字），必须先调用 get_living_expenses 获取"),
            "name" to Schema(type = Type.STRING, description = "支出名称，如「房租」"),
            "amount" to Schema(type = Type.NUMBER, description = "金额（元），必须大于 0，如 3200"),
            "period" to Schema(type = Type.STRING, description = "周期，必须为 MONTHLY 或 YEARLY")
        ),
        required = listOf("id", "name", "amount", "period")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val id = args.intArg("id")?.toLong() ?: return mapOf("error" to "缺少 id 参数")
        val name = args.stringArg("name") ?: return mapOf("error" to "缺少 name 参数")
        val amount = args.doubleArg("amount") ?: return mapOf("error" to "缺少 amount 参数")
        val period = args.stringArg("period") ?: return mapOf("error" to "缺少 period 参数")
        if (amount <= 0 || period !in PERIODS) {
            return mapOf("error" to "金额须大于 0，周期须为 MONTHLY 或 YEARLY")
        }
        return runCatching {
            livingExpenseRepository.updateExpense(id, name, amount, period)
            mapOf("ok" to true, "id" to id)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "修改失败")) }
    }
}

class RemoveLivingExpenseTool(
    private val livingExpenseRepository: LivingExpenseRepository,
) : WriteTool(
    name = "remove_living_expense",
    description = "删除一条生活支出（不可恢复，请谨慎）。id 必须先调用 get_living_expenses 获取。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "id" to Schema(type = Type.INTEGER, description = "支出记录 id（数字），必须先调用 get_living_expenses 获取")
        ),
        required = listOf("id")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val id = args.intArg("id")?.toLong() ?: return mapOf("error" to "缺少 id 参数")
        return runCatching {
            livingExpenseRepository.deleteExpense(id)
            mapOf("ok" to true, "id" to id)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "删除失败")) }
    }
}

class SetFireGoalTool(
    private val fireGoalRepository: FireGoalRepository,
) : WriteTool(
    name = "set_fire_goal",
    description = "设置 FIRE 财务自由目标资产金额。amount 单位元，必须大于 0，如 2000000（200 万）。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf("amount" to Schema(type = Type.NUMBER, description = "目标金额（元），必须大于 0，如 2000000")),
        required = listOf("amount")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val amount = args.doubleArg("amount") ?: return mapOf("error" to "缺少 amount 参数")
        if (amount <= 0) return mapOf("error" to "目标金额必须大于 0")
        return runCatching {
            fireGoalRepository.saveGoal(amount)
            mapOf("ok" to true, "amount" to amount)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "设置失败")) }
    }
}
