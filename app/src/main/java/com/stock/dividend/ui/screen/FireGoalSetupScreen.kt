package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.ui.component.AppCard
import com.stock.dividend.ui.component.AppCardTone
import com.stock.dividend.ui.component.CompactTopAppBar
import com.stock.dividend.viewmodel.FireGoalViewModel
import com.stock.dividend.ui.component.AppTextButton
import com.stock.dividend.ui.component.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireGoalSetupScreen(
    onBack: () -> Unit,
    viewModel: FireGoalViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved, uiState.deleted) {
        if (uiState.saved || uiState.deleted) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            CompactTopAppBar(
                title = "FIRE 目标",
                onBack = onBack,
                actions = {
                    AppTextButton(
                        onClick = viewModel::saveGoal,
                        enabled = !uiState.isSaving,
                        text = "确认",
                    )
                }
            )
        }
    ) { padding ->
        Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
    ) {
        AppCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "目标年度支出",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "设置 FIRE 退休后每年的目标支出金额，当股息收入覆盖支出时即达成财务自由",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    AppTextField(
                        value = uiState.amountInput,
                        onValueChange = viewModel::onAmountChanged,
                        label = { Text("目标金额（元）") },
                        placeholder = { Text("例如：200,000") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = uiState.error != null,
                        supportingText = uiState.error?.let {
                            { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        prefix = { Text("¥") }
                    )
                }
            }

            if (uiState.existingGoal != null) {
                Spacer(modifier = Modifier.height(24.dp))

                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    AppTextButton(
                        onClick = viewModel::showDeleteDialog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    ) {
                        Text(
                            "删除目标",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    if (uiState.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDialog,
            title = { Text("确认删除") },
            text = { Text("确认删除 FIRE 目标？删除后主页将不再显示进度。") },
            confirmButton = {
                AppTextButton(onClick = viewModel::deleteGoal) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                AppTextButton(
                    onClick = viewModel::dismissDeleteDialog,
                    text = "取消",
                )
            }
        )
    }
}
