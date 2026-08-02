package com.stock.dividend.data.agent.tools

import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_BUY
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_SELL
import com.stock.dividend.data.local.entity.STRATEGY_DIRECTION_WATCH
import com.stock.dividend.data.local.entity.STRATEGY_STATUS_ACTIVE
import com.stock.dividend.data.local.entity.TradeStrategyEntity
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.risksToJsonString
import java.time.LocalDate
import java.util.UUID

/**
 * 把对话中涌现的投资思路沉淀为一条全局策略（写入策略库）。
 *
 * 经 [WriteTool] 自动套用确认门：模型调用后会先在聊天界面弹出确认框，
 * 用户同意后才真正落库（与 add_stock / add_transaction 等写工具一致）。
 */
class AddTradeStrategyTool(
    private val tradeStrategyRepository: TradeStrategyRepository,
) : WriteTool(
    name = "add_trade_strategy",
    description = "把一条投资策略写入全局策略库（用户投资原则，对所有股票通用）。" +
        "direction 必须为 BUY（买入）、SELL（卖出）或 WATCH（观察）；" +
        "targetText 为标的或主题（如「招商银行」「银行股」「红利板块」）；" +
        "reasoning 为买入/卖出/观察的逻辑；risks 为风险点字符串数组，可不传。",
    parameters = Schema(
        type = Type.OBJECT,
        properties = mapOf(
            "targetText" to Schema(
                type = Type.STRING,
                description = "策略标的或主题，如「招商银行」「银行股」「红利板块」"
            ),
            "direction" to Schema(
                type = Type.STRING,
                description = "策略方向，必须为 BUY（买入）、SELL（卖出）或 WATCH（观察），大写"
            ),
            "reasoning" to Schema(
                type = Type.STRING,
                description = "策略逻辑/理由，如「股息率 > 6% 且破净，安全边际充足」"
            ),
            "risks" to Schema(
                type = Type.ARRAY,
                items = Schema(type = Type.STRING),
                description = "风险点数组，如 [\"估值修复不及预期\",\"分红下滑\"]，可不传"
            ),
            "validUntil" to Schema(
                type = Type.STRING,
                description = "可选：策略有效期截止日 yyyy-MM-dd，不传表示长期有效"
            )
        ),
        required = listOf("targetText", "direction", "reasoning")
    ),
) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
        val targetText = args.stringArg("targetText") ?: return mapOf("error" to "缺少 targetText 参数")
        val direction = args.stringArg("direction")?.uppercase() ?: return mapOf("error" to "缺少 direction 参数")
        if (direction !in VALID_DIRECTIONS) {
            return mapOf("error" to "direction 只能是 BUY、SELL 或 WATCH")
        }
        val reasoning = args.stringArg("reasoning") ?: return mapOf("error" to "缺少 reasoning 参数")
        val risks = args.stringListArg("risks").filter { it.isNotBlank() }
        val validUntil = args.stringArg("validUntil")?.takeIf { it.isNotBlank() }
        return runCatching {
            val entity = TradeStrategyEntity(
                id = UUID.randomUUID().toString(),
                targetText = targetText,
                direction = direction,
                reasoning = reasoning,
                risks = risksToJsonString(risks),
                validUntil = validUntil,
                sourceNote = "AI 对话",
                rawOcrText = "",
                status = STRATEGY_STATUS_ACTIVE,
            )
            tradeStrategyRepository.upsert(entity)
            mapOf(
                "ok" to true,
                "id" to entity.id,
                "targetText" to targetText,
                "direction" to direction,
            )
        }.getOrElse { e -> mapOf("error" to (e.message ?: "保存策略失败")) }
    }

    private companion object {
        val VALID_DIRECTIONS = setOf(STRATEGY_DIRECTION_BUY, STRATEGY_DIRECTION_SELL, STRATEGY_DIRECTION_WATCH)
    }
}
