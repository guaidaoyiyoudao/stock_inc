package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.local.entity.TransactionEntity
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TransactionRepository
import java.time.LocalDate

class AddStockTool(
    private val stockRepository: StockRepository,
) : WriteTool(
    name = "add_stock",
    description = "添加自选股票；shares>0 时同时记录一笔买入交易。code 推荐 6 位数字代码或股票名称，带前缀代码会自动归一化。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码（sh.600519 / sz.000001）会自动归一化，同样可用"
            ),
            "shares" to Schema(type = Type.INTEGER, description = "整数股数，默认 0（0=仅观察不持仓），如 100"),
            "costPerShare" to Schema(type = Type.NUMBER, description = "每股成本价（元），默认 0，如 12.50")
        ),
        required = listOf("code")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        val shares = args.intArg("shares") ?: 0
        val cost = args.doubleArg("costPerShare") ?: 0.0
        if (shares < 0) return mapOf("error" to "股数不能为负")
        if (cost < 0) return mapOf("error" to "成本不能为负")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val result = stockRepository.addStock(stock, shares, cost)
            if (result.isSuccess) {
                mapOf("ok" to true, "code" to stock.code, "name" to stock.name, "shares" to shares)
            } else {
                mapOf("error" to (result.exceptionOrNull()?.message ?: "添加失败"))
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: "添加失败")) }
    }
}

class RemoveStockTool(
    private val stockRepository: StockRepository,
) : WriteTool(
    name = "remove_stock",
    description = "从自选/持仓中删除一只股票（不可恢复，请谨慎）。code 推荐 6 位数字代码或股票名称，带前缀代码会自动归一化。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
            )
        ),
        required = listOf("code")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            stockRepository.removeStock(stock.code)
            mapOf("ok" to true, "code" to stock.code)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "删除失败")) }
    }
}

class UpdateHoldingTool(
    private val stockRepository: StockRepository,
) : WriteTool(
    name = "update_holding",
    description = "直接修改持仓股数与成本价（shares 与 costPerShare 都必须提供）。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
            ),
            "shares" to Schema(type = Type.INTEGER, description = "最新整数股数（>=0），如 100"),
            "costPerShare" to Schema(type = Type.NUMBER, description = "最新每股成本价（元，>=0），如 12.50")
        ),
        required = listOf("code", "shares", "costPerShare")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        val shares = args.intArg("shares") ?: return mapOf("error" to "缺少 shares 参数")
        val cost = args.doubleArg("costPerShare") ?: return mapOf("error" to "缺少 costPerShare 参数")
        if (shares < 0 || cost < 0) return mapOf("error" to "股数与成本不能为负")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            stockRepository.updateShares(stock.code, shares)
            stockRepository.updateCostPerShare(stock.code, cost)
            mapOf("ok" to true, "code" to stock.code, "shares" to shares, "costPerShare" to cost)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "修改失败")) }
    }
}

class AddTransactionTool(
    private val stockRepository: StockRepository,
    private val transactionRepository: TransactionRepository,
) : WriteTool(
    name = "add_transaction",
    description = "记录一笔买入/卖出交易并重算该股持仓。type 必须为 BUY 或 SELL；shares 为正整数；price 为成交价（元）；date 格式 yyyy-MM-dd（如 2026-08-01），缺省今天。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
            ),
            "type" to Schema(type = Type.STRING, description = "交易方向，必须为 BUY（买入）或 SELL（卖出），大写"),
            "shares" to Schema(type = Type.INTEGER, description = "股数（正整数），如 100"),
            "price" to Schema(type = Type.NUMBER, description = "成交价（元，>=0），如 12.50"),
            "date" to Schema(type = Type.STRING, description = "可选：成交日期 yyyy-MM-dd，默认今天")
        ),
        required = listOf("code", "type", "shares", "price")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        val type = args.stringArg("type")?.uppercase() ?: return mapOf("error" to "缺少 type 参数")
        if (type != "BUY" && type != "SELL") return mapOf("error" to "type 只能是 BUY 或 SELL")
        val shares = args.intArg("shares") ?: return mapOf("error" to "缺少 shares 参数")
        val price = args.doubleArg("price") ?: return mapOf("error" to "缺少 price 参数")
        if (shares <= 0) return mapOf("error" to "股数必须大于 0")
        if (price < 0) return mapOf("error" to "价格不能为负")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            val date = args.stringArg("date") ?: LocalDate.now().toString()
            transactionRepository.addTransaction(
                TransactionEntity(
                    stockCode = stock.code,
                    type = type,
                    shares = shares,
                    price = price,
                    date = date
                )
            )
            stockRepository.recomputeHolding(stock.code)
            mapOf("ok" to true, "code" to stock.code, "type" to type, "shares" to shares)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "记录失败")) }
    }
}

class SetStockTagsTool(
    private val stockRepository: StockRepository,
) : WriteTool(
    name = "set_stock_tags",
    description = "覆盖设置一只股票的标签列表。tags 为字符串数组，如 [\"红利\",\"核心\"]。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "code" to Schema(
                type = Type.STRING,
                description = "股票代码或名称：推荐 6 位数字代码（如 600519）或股票名称；带前缀代码会自动归一化"
            ),
            "tags" to Schema(
                type = Type.ARRAY,
                items = Schema(type = Type.STRING),
                description = "标签数组，如 [\"红利\",\"核心\"]"
            )
        ),
        required = listOf("code", "tags")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val code = args.stringArg("code") ?: return mapOf("error" to "缺少 code 参数")
        val tags = args.stringListArg("tags")
        return runCatching {
            val stock = stockRepository.resolveStock(code)
                ?: return@runCatching mapOf("error" to "未找到股票：$code")
            stockRepository.setStockTags(stock.code, tags)
            mapOf("ok" to true, "code" to stock.code, "tags" to tags)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "设置失败")) }
    }
}

class UpdateIndustryTargetTool(
    private val stockRepository: StockRepository,
) : WriteTool(
    name = "update_industry_target",
    description = "设置某行业的目标配比。weight 为百分比数值 0-100，如 30 表示 30%。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "industry" to Schema(type = Type.STRING, description = "行业名，如「银行」"),
            "weight" to Schema(type = Type.NUMBER, description = "目标权重百分比（0-100），如 30 表示 30%")
        ),
        required = listOf("industry", "weight")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val industry = args.stringArg("industry") ?: return mapOf("error" to "缺少 industry 参数")
        val weight = args.doubleArg("weight") ?: return mapOf("error" to "缺少 weight 参数")
        if (weight < 0 || weight > 100) return mapOf("error" to "权重须在 0-100 之间")
        return runCatching {
            stockRepository.updateIndustryTarget(industry, weight)
            mapOf("ok" to true, "industry" to industry, "weight" to weight)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "设置失败")) }
    }
}

class UpdateNotificationRuleTool(
    private val notificationRuleRepository: NotificationRuleRepository,
) : WriteTool(
    name = "update_notification_rule",
    description = "更新全局评估门槛（用于单股评估的买入股息率门槛）。minYield=买入建议最低股息率%，boostYield=加强信号股息率%，要求 0 <= minYield <= boostYield，如 2.0 与 5.0。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "minYield" to Schema(type = Type.NUMBER, description = "最低股息率（%），如 2.0 表示 2%"),
            "boostYield" to Schema(type = Type.NUMBER, description = "加强信号股息率（%），如 5.0 表示 5%；必须 >= minYield")
        ),
        required = listOf("minYield", "boostYield")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val min = args.doubleArg("minYield") ?: return mapOf("error" to "缺少 minYield 参数")
        val boost = args.doubleArg("boostYield") ?: return mapOf("error" to "缺少 boostYield 参数")
        if (min < 0 || boost < 0 || boost < min) return mapOf("error" to "参数非法：0 <= minYield <= boostYield")
        return runCatching {
            notificationRuleRepository.saveEvalThresholds(min, boost)
            mapOf("ok" to true, "minYield" to min, "boostYield" to boost)
        }.getOrElse { e -> mapOf("error" to (e.message ?: "设置失败")) }
    }
}
