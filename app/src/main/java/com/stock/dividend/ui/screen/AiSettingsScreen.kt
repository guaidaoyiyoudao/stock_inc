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
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stock.dividend.ui.component.AppButton
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField
import com.stock.dividend.viewmodel.AiSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 助手设置") },
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
            // ── 系统提示词 ──
            Text(
                text = "系统提示词",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "附加在默认提示词之后（默认包含工具调用与数据准确性契约，不可移除）。留空则用默认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppTextField(
                value = state.systemPromptInput,
                onValueChange = viewModel::onSystemPromptChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：回答时多用表格；语气轻松些；优先推荐高股息蓝筹…") },
                maxLines = 8
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                AppTextButton(
                    onClick = viewModel::restoreDefaultPrompt,
                    leadingIcon = Icons.Filled.Restore,
                    text = "恢复默认",
                )
            }

            // ── 其他相关设置 ──
            Text(
                text = "模型行为",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            AppCard(tone = AppCardTone.List) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppTextField(
                        value = state.temperatureInput,
                        onValueChange = viewModel::onTemperatureChanged,
                        label = { Text("回答温度 (0~2)") },
                        singleLine = true,
                        supportingText = {
                            Text("越大越发散，越小越确定。留空用模型默认。")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    AppTextField(
                        value = state.maxTokensInput,
                        onValueChange = viewModel::onMaxTokensChanged,
                        label = { Text("最大输出长度") },
                        singleLine = true,
                        supportingText = {
                            Text("单轮回答的 token 上限。留空用模型默认。")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            state.error?.let { errorMsg ->
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            AppButton(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
                text = "保存",
            )
            if (state.saved) {
                Text(
                    text = "已保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
