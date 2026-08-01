package com.stock.dividend.data.agent

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.stock.dividend.data.agent.tools.AddLivingExpenseTool
import com.stock.dividend.data.agent.tools.AddStockTool
import com.stock.dividend.data.agent.tools.AddTransactionTool
import com.stock.dividend.data.agent.tools.GetBuyThresholdTool
import com.stock.dividend.data.agent.tools.GetDividendForecastTool
import com.stock.dividend.data.agent.tools.GetDividendHistoryTool
import com.stock.dividend.data.agent.tools.GetHoldingsTool
import com.stock.dividend.data.agent.tools.GetIndustryAllocationTool
import com.stock.dividend.data.agent.tools.GetNotificationRulesTool
import com.stock.dividend.data.agent.tools.GetPortfolioSummaryTool
import com.stock.dividend.data.agent.tools.GetStockEvaluationTool
import com.stock.dividend.data.agent.tools.GetStockInfoTool
import com.stock.dividend.data.agent.tools.GetTransactionsTool
import com.stock.dividend.data.agent.tools.GetUserStrategiesTool
import com.stock.dividend.data.agent.tools.GetLivingExpensesTool
import com.stock.dividend.data.agent.tools.GetValuationTool
import com.stock.dividend.data.agent.tools.RemoveLivingExpenseTool
import com.stock.dividend.data.agent.tools.RemoveStockTool
import com.stock.dividend.data.agent.tools.SearchStockTool
import com.stock.dividend.data.agent.tools.SetFireGoalTool
import com.stock.dividend.data.agent.tools.SetStockTagsTool
import com.stock.dividend.data.agent.tools.UpdateHoldingTool
import com.stock.dividend.data.agent.tools.UpdateIndustryTargetTool
import com.stock.dividend.data.agent.tools.UpdateLivingExpenseTool
import com.stock.dividend.data.agent.tools.UpdateNotificationRuleTool
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.FireGoalRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.TransactionRepository
import com.stock.dividend.di.LlmClient
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** 组装 AI Tab 的单 LlmAgent（24 个工具，写操作全部带确认门）。 */
@Singleton
class AiAgentFactory @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val dividendIncomeRepository: DividendIncomeRepository,
    private val bondYieldRepository: BondYieldRepository,
    private val fireGoalRepository: FireGoalRepository,
    private val livingExpenseRepository: LivingExpenseRepository,
    private val transactionRepository: TransactionRepository,
    private val notificationRuleRepository: NotificationRuleRepository,
    private val tradeStrategyRepository: TradeStrategyRepository,
    @LlmClient private val llmClient: OkHttpClient,
) {
    suspend fun create(config: LlmConfig): LlmAgent {
        val strategies = tradeStrategyRepository.activeStrategies()
        val marketTools = listOf(
            GetStockInfoTool(stockRepository, dividendRepository),
            SearchStockTool(stockRepository),
            GetDividendHistoryTool(dividendRepository, stockRepository),
            GetDividendForecastTool(dividendRepository, stockRepository),
            GetValuationTool(stockRepository, dividendRepository),
            GetBuyThresholdTool(stockRepository, dividendRepository, bondYieldRepository),
            GetStockEvaluationTool(stockRepository, dividendRepository, notificationRuleRepository),
        )
        val portfolioTools = listOf(
            GetHoldingsTool(stockRepository),
            GetPortfolioSummaryTool(stockRepository, dividendIncomeRepository, fireGoalRepository, livingExpenseRepository),
            GetIndustryAllocationTool(stockRepository),
            GetTransactionsTool(stockRepository, transactionRepository),
            GetNotificationRulesTool(stockRepository, notificationRuleRepository),
            GetUserStrategiesTool(tradeStrategyRepository),
        )
        val actionTools = listOf(
            AddStockTool(stockRepository),
            RemoveStockTool(stockRepository),
            UpdateHoldingTool(stockRepository),
            AddTransactionTool(stockRepository, transactionRepository),
            SetStockTagsTool(stockRepository),
            UpdateIndustryTargetTool(stockRepository),
            UpdateNotificationRuleTool(notificationRuleRepository),
        )
        val financeTools = listOf(
            GetLivingExpensesTool(livingExpenseRepository),
            AddLivingExpenseTool(livingExpenseRepository),
            UpdateLivingExpenseTool(livingExpenseRepository),
            RemoveLivingExpenseTool(livingExpenseRepository),
            SetFireGoalTool(fireGoalRepository),
        )
        return LlmAgent(
            name = AGENT_NAME,
            model = OpenAiCompatibleModel(config, llmClient),
            instruction = Instruction(AgentInstructionBuilder.build(strategies)),
            tools = marketTools + portfolioTools + actionTools + financeTools,
        )
    }

    companion object {
        const val AGENT_NAME = "ai_tab_agent"
    }
}
