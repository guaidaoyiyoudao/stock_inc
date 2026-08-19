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
import com.stock.dividend.data.agent.tools.GetCompareStocksTool
import com.stock.dividend.data.agent.tools.GetDividendIncomeTool
import com.stock.dividend.data.agent.tools.GetDividendForecastTool
import com.stock.dividend.data.agent.tools.GetDividendHistoryTool
import com.stock.dividend.data.agent.tools.GetDividendMetricsTool
import com.stock.dividend.data.agent.tools.GetDragonTigerTool
import com.stock.dividend.data.agent.tools.GetEtfInfoTool
import com.stock.dividend.data.agent.tools.GetFinancialStatementsTool
import com.stock.dividend.data.agent.tools.GetFundamentalsTool
import com.stock.dividend.data.agent.tools.GetGridPlansTool
import com.stock.dividend.data.agent.tools.GetHoldingsTool
import com.stock.dividend.data.agent.tools.GetIndustryAllocationTool
import com.stock.dividend.data.agent.tools.GetIndustryListTool
import com.stock.dividend.data.agent.tools.GetIndustryPeersTool
import com.stock.dividend.data.agent.tools.GetKlineTool
import com.stock.dividend.data.agent.tools.GetLivingExpensesTool
import com.stock.dividend.data.agent.tools.GetMarketIndexTool
import com.stock.dividend.data.agent.tools.GetMarketRankingTool
import com.stock.dividend.data.agent.tools.GetMarketSentimentTool
import com.stock.dividend.data.agent.tools.GetNotificationRulesTool
import com.stock.dividend.data.agent.tools.GetPortfolioSummaryTool
import com.stock.dividend.data.agent.tools.GetPortfolioSignalsTool
import com.stock.dividend.data.agent.tools.GetPortfolioDiagnosisTool
import com.stock.dividend.data.agent.tools.GetResearchReportsTool
import com.stock.dividend.data.agent.tools.GetStockEvaluationTool
import com.stock.dividend.data.agent.tools.GetStockInfoTool
import com.stock.dividend.data.agent.tools.GetStockNewsTool
import com.stock.dividend.data.agent.tools.GetTransactionsTool
import com.stock.dividend.data.agent.tools.GetTreasuryYieldsTool
import com.stock.dividend.data.agent.tools.GetUserStrategiesTool
import com.stock.dividend.data.agent.tools.GetValuationMetricsTool
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
import com.stock.dividend.data.plane.MarketDataPlane
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.FireGoalRepository
import com.stock.dividend.data.repository.GridPlanRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import com.stock.dividend.data.repository.AiAgentConfigSource
import com.stock.dividend.data.repository.LlmConfig
import com.stock.dividend.data.repository.PortfolioDiagnosisAssembler
import com.stock.dividend.data.repository.NotificationRuleRepository
import com.stock.dividend.data.repository.StockRepository
import com.stock.dividend.data.repository.TradeStrategyRepository
import com.stock.dividend.data.repository.TransactionRepository
import com.stock.dividend.di.LlmClient
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** 组装 AI Tab 的单 LlmAgent（47 个工具：34 只读 + 13 写，写操作全部带确认门）。 */
@Singleton
class AiAgentFactory @Inject constructor(
    /** 读股市数据唯一入口（数据平面）；仓库仅剩写操作与本地域数据。 */
    private val marketDataPlane: MarketDataPlane,
    /** 仅写工具与本地域数据用（add_stock/update_holding/标签/交易等）。 */
    private val stockRepository: StockRepository,
    private val dividendIncomeRepository: DividendIncomeRepository,
    private val fireGoalRepository: FireGoalRepository,
    private val livingExpenseRepository: LivingExpenseRepository,
    private val transactionRepository: TransactionRepository,
    private val gridPlanRepository: GridPlanRepository,
    private val notificationRuleRepository: NotificationRuleRepository,
    private val tradeStrategyRepository: TradeStrategyRepository,
    private val diagnosisAssembler: PortfolioDiagnosisAssembler,
    private val agentConfigSource: AiAgentConfigSource,
    @LlmClient private val llmClient: OkHttpClient,
) {
    suspend fun create(config: LlmConfig): LlmAgent {
        val strategies = tradeStrategyRepository.activeStrategies()
        val agentConfig = agentConfigSource.observe().first()
        // 完全用用户配置的 model，路径按 model 决定：
        // - deepseek-v4-flash：推理模型，只在 Responses API 上可用 → 走 /responses（有思考过程），
        //   联网开关控制是否注入 web_search。
        // - 其他模型（deepseek-v4-pro / deepseek-chat / 别的厂商）：走 Chat Completions（无思考）。
        // deepseek-chat 已下线，用户若本地残留该值 → 走 Chat Completions 时会被服务端拒绝，
        // 需用户在设置页改成 deepseek-v4-flash / deepseek-v4-pro。
        val useResponses = config.model == DEEPSEEK_RESPONSES_MODEL
        val includeWebSearch = useResponses && agentConfig.webSearch
        val model = OpenAiCompatibleModel(
            config = config,
            client = llmClient,
            useResponsesApi = useResponses,
            includeWebSearch = includeWebSearch,
            effectiveModel = config.model,
        )
        val marketTools = listOf(
            // ── 基础行情与估值 ──
            GetStockInfoTool(marketDataPlane),
            SearchStockTool(marketDataPlane),
            GetDividendHistoryTool(marketDataPlane),
            GetDividendForecastTool(marketDataPlane),
            GetBuyThresholdTool(marketDataPlane),
            GetStockEvaluationTool(marketDataPlane, notificationRuleRepository),
            GetFundamentalsTool(marketDataPlane),
            GetKlineTool(marketDataPlane),
            // ── 估值指标与资金面 ──
            GetValuationMetricsTool(marketDataPlane),
            GetCapitalFlowTool(marketDataPlane),
            GetDragonTigerTool(marketDataPlane),
            GetMarketSentimentTool(marketDataPlane),
            // ── 财务报表与分红深度 ──
            GetFinancialStatementsTool(marketDataPlane),
            GetDividendMetricsTool(marketDataPlane),
            // ── 行业对比 ──
            GetIndustryListTool(marketDataPlane),
            GetIndustryPeersTool(marketDataPlane),
            // ── 资讯与研报 ──
            GetResearchReportsTool(marketDataPlane),
            GetStockNewsTool(marketDataPlane),
            // ── 市场广度 ──
            GetMarketIndexTool(marketDataPlane),
            GetEtfInfoTool(marketDataPlane),
            GetTreasuryYieldsTool(marketDataPlane),
            // ── 全市场榜单 ──
            GetMarketRankingTool(marketDataPlane),
        )
        val portfolioTools = listOf(
            GetHoldingsTool(marketDataPlane, stockRepository),
            GetPortfolioSummaryTool(marketDataPlane, dividendIncomeRepository, fireGoalRepository, livingExpenseRepository),
            GetIndustryAllocationTool(marketDataPlane, stockRepository),
            GetTransactionsTool(marketDataPlane, transactionRepository),
            GetNotificationRulesTool(marketDataPlane, notificationRuleRepository),
            GetUserStrategiesTool(tradeStrategyRepository),
            GetPortfolioSignalsTool(marketDataPlane, notificationRuleRepository),
            GetDividendIncomeTool(dividendIncomeRepository, marketDataPlane),
            GetGridPlansTool(marketDataPlane, gridPlanRepository, transactionRepository),
            // ── 组合分析（2026-08-15 新增）──
            GetCompareStocksTool(marketDataPlane, notificationRuleRepository),
            GetPortfolioDiagnosisTool(marketDataPlane, diagnosisAssembler, gridPlanRepository, transactionRepository),
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
            model = model,
            instruction = Instruction(
                AgentInstructionBuilder.build(strategies, agentConfig.systemPrompt, agentConfig.webSearch)
            ),
            // temperature/maxOutputTokens：经 ADK 注入 LlmRequest.config，
            // 由 OpenAiProtocol / DeepSeekResponsesProtocol 自动透传到请求体（null 表示用模型默认）。
            generateContentConfig = GenerateContentConfig(
                temperature = agentConfig.temperature,
                maxOutputTokens = agentConfig.maxTokens,
            ),
            tools = marketTools + portfolioTools + actionTools + financeTools,
        )
    }

    companion object {
        const val AGENT_NAME = "ai_tab_agent"
        /** DeepSeek Responses API 当前唯一支持的模型。 */
        const val DEEPSEEK_RESPONSES_MODEL = "deepseek-v4-flash"
    }
}
