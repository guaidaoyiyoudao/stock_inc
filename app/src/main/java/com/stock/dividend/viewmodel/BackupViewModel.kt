package com.stock.dividend.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.backup.BackupMetadata
import com.stock.dividend.data.repository.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val isLoading: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val backupMetadata: BackupMetadata? = null,
    val showConfirmRestoreDialog: Boolean = false,
    val pendingRestoreUri: Uri? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    fun exportBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            backupRepository.exportToJson(context, uri).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "数据已成功导出",
                        isError = false
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "导出失败：${e.message}",
                        isError = true
                    )
                }
            )
        }
    }

    fun selectImportFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            backupRepository.validateBackup(context, uri).fold(
                onSuccess = { metadata ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        backupMetadata = metadata,
                        showConfirmRestoreDialog = true,
                        pendingRestoreUri = uri
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "无效的备份文件：${e.message}",
                        isError = true
                    )
                }
            )
        }
    }

    fun confirmRestore(context: Context) {
        val uri = _uiState.value.pendingRestoreUri ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                showConfirmRestoreDialog = false,
                isLoading = true,
                pendingRestoreUri = null
            )
            backupRepository.importFromJson(context, uri).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "数据已成功恢复",
                        isError = false
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "导入失败：${e.message}",
                        isError = true
                    )
                }
            )
        }
    }

    fun dismissConfirmDialog() {
        _uiState.value = _uiState.value.copy(
            showConfirmRestoreDialog = false,
            backupMetadata = null,
            pendingRestoreUri = null
        )
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, isError = false)
    }
}
