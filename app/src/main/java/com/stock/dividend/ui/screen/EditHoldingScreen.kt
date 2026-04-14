package com.stock.dividend.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.viewmodel.EditHoldingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHoldingScreen(
    onBack: () -> Unit,
    viewModel: EditHoldingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑持仓") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.saveHolding()
                            onBack()
                        },
                        enabled = uiState.sharesError == null
                    ) {
                        Text("保存")
                    }
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
            if (uiState.stockName != null) {
                Text(
                    text = "${uiState.stockName} (${uiState.stockCode})",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = uiState.sharesInput,
                onValueChange = viewModel::onSharesChanged,
                label = { Text("持有股数") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = uiState.sharesError != null,
                supportingText = uiState.sharesError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "股息率档位",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val periods = listOf("1" to "1年", "3" to "3年", "5" to "5年")
                periods.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size),
                        onClick = { viewModel.onYieldPeriodChanged(value) },
                        selected = uiState.yieldPeriod == value
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
