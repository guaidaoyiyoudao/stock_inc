package com.stock.dividend.data.agent

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.types.GenerateContentConfig
import com.stock.dividend.data.agent.tools.AddLivingExpenseTool
import com.stock.dividend.data.agent.tools.AddStockTool
import com.stock.dividend.data.agent.tools.AddTradeStrategyTool
import com.stock.dividend.data.agent.tools.AddTransactionTool
import com.stock.dividend.data.agent.tools.GetBuyThresholdTool
import com.stock.dividend.data.agent.tools.GetCapitalFlowTool
import com.stock.dividend.data.agent.tools.GetDividendIncomeTool
import com.stock.dividend.data.agent.tools.GetDividendForecastTool
import com.stock.dividend.data.agent.tools.GetDividendHistoryTool
import com.stock.dividend.data.agent.tools.GetDividendMetricsTool
import com.stock.dividend.data.agent.tools.GetDragonTigerTool
import com.stock.dividend.data.agent.tools.GetEtfInfoTool
import com.stock.dividend.data.agent.tools.GetFinancialStatementsTool
import com.stock.dividend.data.agent.tools.GetFundamentalsTool
import com.stock.dividend.data.agent.tools.GetHoldingsTool
import com.stock.dividend.data.agent.tools.GetIndustryAllocationTool
import com.stock.dividend.data.agent.tools.GetIndustryListTool
import com.stock.dividend.data.agent.tools.GetIndustryPeersTool
import com.stock.dividend.data.agent.tools.GetKlineTool
import com.stock.dividend.data.agent.tools.GetLivingExpensesTool
import com.stock.dividend.data.agent.tools.GetMarketIndexTool
import com.stock.dividend.data.agent.tools.GetMarketSentimentTool
import com.stock.dividend.data.agent.tools.GetNotificationRulesTool
import com.stock.dividend.data.agent.tools.GetPortfolioSummaryTool
import com.stock.dividend.data.agent.tools.GetPortfolioSignalsTool
import com.stock.dividend.data.agent.tools.GetResearchReportsTool
import com.stock.dividend.data.agent.tools.GetStockEvaluationTool
import com.stock.dividend.data.agent.tools.GetStockInfoTool
import com.stock.dividend.data.agent.tools.GetStockNewsTool
import com.stock.dividend.data.agent.tools.GetTransactionsTool
import com.stock.dividend.data.agent.tools.GetTreasuryYieldsTool
import com.stock.dividend.data.agent.tools.GetUserStrategiesTool
import com.stock.dividend.data.agent.tools.GetValuationMetricsTool
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
import com.stock.dividend.data.agent.tools.UpdateStockSettingsTool
import com.stock.dividend.data.repository.BondYieldRepository
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.DividendRepository
import com.stock.dividend.data.repository.FireGoalRepository
import com.stock.dividend.data.repository.FinancialStatementsRepository
import com.stock.dividend.data.repository.FundamentalsCacheRepository
import com.stock.dividend.data.repository.KlineRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.AiAgentConfigSource
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.data.repository.MarketDataRepository
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.ResearchRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.TransactionRepository
import com.stock.dividend.di.LlmClient
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** 组装 AI Tab 的单 LlmAgent（44 个工具：31 只读 + 13 写，写操作全部带确认门）。 */
@Singleton
class AiAgentFactory @Inject constructor(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val dividendIncomeRepository: DividendIncomeRepository,
    private val bondYieldRepository: BondYieldRepository,
    private val fundamentalsCacheRepository: FundamentalsCacheRepository,
    private val financialStatementsRepository: FinancialStatementsRepository,
    private val klineRepository: KlineRepository,
    private val fireGoalRepository: FireGoalRepository,
    private val livingExpenseRepository: LivingExpenseRepository,
    private val transactionRepository: TransactionRepository,
    private val notificationRuleRepository: NotificationRuleRepository,
    private val tradeStrategyRepository: TradeStrategyRepository,
    private val marketDataRepository: MarketDataRepository,
    private val researchRepository: ResearchRepository,
    private val agentConfigSource: AiAgentConfigSource,
    @LlmClient private val llmClient: OkHttpClient,
) {
    suspend fun create(config: LlmConfig): LlmAgent {
        val strategies = tradeStrategyRepository.activeStrategies()
        val agentConfig = agentConfigSource.observe().first()
        val marketTools = listOf(
            // ── 基础行情与估值 ──
            GetStockInfoTool(stockRepository, dividendRepository),
            SearchStockTool(stockRepository),
            GetDividendHistoryTool(dividendRepository, stockRepository),
            GetDividendForecastTool(dividendRepository, stockRepository),
            GetValuationTool(stockRepository, dividendRepository),
            GetBuyThresholdTool(stockRepository, dividendRepository, bondYieldRepository),
            GetStockEvaluationTool(stockRepository, dividendRepository, notificationRuleRepository),
            GetFundamentalsTool(stockRepository, dividendRepository, fundamentalsCacheRepository),
            GetKlineTool(stockRepository, klineRepository),
            // ── 估值指标与资金面 ──
            GetValuationMetricsTool(stockRepository),
            GetCapitalFlowTool(stockRepository, marketDataRepository),
            GetDragonTigerTool(marketDataRepository),
            GetMarketSentimentTool(marketDataRepository),
            // ── 财务报表与分红深度 ──
            GetFinancialStatementsTool(stockRepository, financialStatementsRepository),
            GetDividendMetricsTool(stockRepository, dividendRepository),
            // ── 行业对比 ──
            GetIndustryListTool(marketDataRepository),
            GetIndustryPeersTool(stockRepository, marketDataRepository),
            // ── 资讯与研报 ──
            GetResearchReportsTool(stockRepository, researchRepository),
            GetStockNewsTool(stockRepository, researchRepository),
            // ── 市场广度 ──
            GetMarketIndexTool(marketDataRepository),
            GetEtfInfoTool(marketDataRepository),
            GetTreasuryYieldsTool(bondYieldRepository),
        )
        val portfolioTools = listOf(
            GetHoldingsTool(stockRepository),
            GetPortfolioSummaryTool(stockRepository, dividendIncomeRepository, fireGoalRepository, livingExpenseRepository),
            GetIndustryAllocationTool(stockRepository),
            GetTransactionsTool(stockRepository, transactionRepository),
            GetNotificationRulesTool(stockRepository, notificationRuleRepository),
            GetUserStrategiesTool(tradeStrategyRepository),
            GetPortfolioSignalsTool(stockRepository, dividendRepository, notificationRuleRepository),
            GetDividendIncomeTool(dividendIncomeRepository, stockRepository),
        )
        val actionTools = listOf(
            AddStockTool(stockRepository),
            RemoveStockTool(stockRepository),
            UpdateHoldingTool(stockRepository),
            AddTransactionTool(stockRepository, transactionRepository),
            SetStockTagsTool(stockRepository),
            UpdateIndustryTargetTool(stockRepository),
            UpdateNotificationRuleTool(stockRepository, notificationRuleRepository),
            UpdateStockSettingsTool(stockRepository),
            AddTradeStrategyTool(tradeStrategyRepository),
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
            instruction = Instruction(AgentInstructionBuilder.build(strategies, agentConfig.systemPrompt)),
            // temperature/maxOutputTokens：经 ADK 注入 LlmRequest.config，
            // 由 OpenAiProtocol 自动透传到 OpenAI 请求体（null 表示用模型默认）。
            generateContentConfig = GenerateContentConfig(
                temperature = agentConfig.temperature,
                maxOutputTokens = agentConfig.maxTokens,
            ),
            tools = marketTools + portfolioTools + actionTools + financeTools,
        )
    }

    companion object {
        const val AGENT_NAME = "ai_tab_agent"
    }
}
