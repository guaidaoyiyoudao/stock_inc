package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.data.repository.LlmProviderPreset
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.viewmodel.NotificationSettingsViewModel

/**
 * 「AI 与策略」设置页（设置主页的二级页面）。
 *
 * 含 LLM 配置（用于一键评估的 AI 解读）与「策略库」跳转入口
 * （截图经 AI 分析提取的买卖策略）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmStrategySettingsScreen(
    onBack: () -> Unit,
    onOpenStrategyLibrary: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 与策略") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── LLM 配置 ──
            Text(
                text = "LLM 配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            LlmConfigSettingsContent(viewModel)

            // ── 视觉识别模型（截图导入）──
            VisionConfigSettingsContent(viewModel)

            // ── 策略库（跳转）──
            SettingsNavRow(
                title = "策略库",
                description = "截图经 AI 分析提取的买卖策略，对所有股票通用",
                icon = Icons.Filled.AutoGraph,
                onClick = onOpenStrategyLibrary
            )
        }
    }
}

@Composable
internal fun LlmConfigSettingsContent(viewModel: NotificationSettingsViewModel) {
    val config by viewModel.llmConfigState.collectAsStateWithLifecycle()
    val agentConfig by viewModel.agentConfigState.collectAsStateWithLifecycle()
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var showKey by remember { mutableStateOf(false) }
    val selectedProvider =
        LlmProviderPreset.entries.firstOrNull { it.baseUrl == config.baseUrl } ?: LlmProviderPreset.CUSTOM
    val isDeepSeek = config.baseUrl.contains("deepseek.com")

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "用于一键评估的 AI 解读。Key 仅存本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LlmProviderPreset.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selectedProvider == p,
                    onClick = {
                        viewModel.setLlmProvider(p)
                        if (p != LlmProviderPreset.CUSTOM) {
                            baseUrl = p.baseUrl
                            model = p.defaultModel
                        }
                    }
                )
                Text(p.displayName)
            }
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                AppTextButton(
                    onClick = { showKey = !showKey },
                    text = if (showKey) "隐藏" else "显示",
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            singleLine = true,
            supportingText = {
                Text("AI 聊天发图识别需多模态模型，如 deepseek-v4-flash-vision-exp / glm-4.6v / gpt-4o")
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        AppButton(
            onClick = { viewModel.saveLlmConfig(baseUrl, apiKey, model) },
            text = "保存",
        )

        // ── 联网搜索（AI Tab 对话用）──
        Spacer(Modifier.height(20.dp))
        Text(
            text = "联网搜索",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "开启后，AI 对话可联网查询实时新闻、政策、宏观等资讯（DeepSeek Responses API + web_search 工具）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text("联网搜索", style = MaterialTheme.typography.bodyLarge)
                if (agentConfig.webSearch) {
                    Text(
                        text = if (isDeepSeek) {
                            "已启用，AI 对话会联网检索实时资讯"
                        } else {
                            "⚠ 联网搜索仅 DeepSeek 支持，建议选 DeepSeek"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = agentConfig.webSearch,
                onCheckedChange = { viewModel.saveWebSearch(it) }
            )
        }
    }
}

/** 视觉识别模型配置：截图导入持仓/交易记录用（GLM-4.6V-Flash，智谱 BigModel，免费）。 */
@Composable
internal fun VisionConfigSettingsContent(viewModel: NotificationSettingsViewModel) {
    val config by viewModel.visionConfigState.collectAsStateWithLifecycle()
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var showKey by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "视觉识别模型（截图导入）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "截图导入持仓/交易记录用 GLM-4.6V-Flash 视觉模型（智谱 BigModel，免费）。图片压缩后上传识别，识别失败自动重试；Key 仅存本机。Key 留空时，若上方 LLM 也配的智谱则自动复用其 Key。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        AppTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key（智谱 BigModel）") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                AppTextButton(
                    onClick = { showKey = !showKey },
                    text = if (showKey) "隐藏" else "显示",
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model（默认 glm-4.6v-flash）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        AppButton(
            onClick = { viewModel.saveVisionConfig(apiKey, model) },
            text = "保存",
        )
    }
}
