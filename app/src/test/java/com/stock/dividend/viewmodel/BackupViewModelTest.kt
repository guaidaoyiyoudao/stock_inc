package com.stock.dividend.viewmodel

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.backup.BackupCounts
import com.stock.dividend.data.local.backup.BackupMetadata
import com.stock.dividend.data.local.backup.BackupSummary
import com.stock.dividend.data.repository.BackupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository: BackupRepository = mockk(relaxed = true)
    // Robolectric 提供真实 Context + Uri，替代 mockk 哑参数
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri: Uri = Uri.parse("content://com.stock.dividend.backup/backup.json")
    private val metadata = BackupMetadata(
        appVersion = "2.0.0",
        versionCode = 3,
        exportTimestamp = 1700000000000L,
        dbVersion = 10
    )
    private val summary = BackupSummary(
        metadata = metadata,
        counts = BackupCounts(
            stocks = 5, dividends = 10, transactions = 8,
            dividendIncomeRecords = 4, tradeStrategies = 2, industryTargets = 3
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `export success updates state with success message`() = runTest {
        coEvery { repository.exportToJson(context, uri) } returns Result.success(Unit)
        val viewModel = BackupViewModel(repository)

        viewModel.exportBackup(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.message).isEqualTo("数据已成功导出")
        assertThat(viewModel.uiState.value.isError).isFalse()
    }

    @Test
    fun `export failure updates state with error message`() = runTest {
        coEvery { repository.exportToJson(context, uri) } returns
            Result.failure(Exception("无法创建文件"))
        val viewModel = BackupViewModel(repository)

        viewModel.exportBackup(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.message).contains("导出失败")
        assertThat(viewModel.uiState.value.message).contains("无法创建文件")
        assertThat(viewModel.uiState.value.isError).isTrue()
    }

    @Test
    fun `select import file with valid backup shows confirm dialog`() = runTest {
        coEvery { repository.validateBackup(context, uri) } returns Result.success(summary)
        val viewModel = BackupViewModel(repository)

        viewModel.selectImportFile(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.showConfirmRestoreDialog).isTrue()
        assertThat(viewModel.uiState.value.backupMetadata?.appVersion).isEqualTo("2.0.0")
        assertThat(viewModel.uiState.value.backupMetadata?.dbVersion).isEqualTo(10)
        assertThat(viewModel.uiState.value.pendingRestoreUri).isEqualTo(uri)
    }

    @Test
    fun `select import file with invalid backup shows error`() = runTest {
        coEvery { repository.validateBackup(context, uri) } returns
            Result.failure(IllegalArgumentException("无效的备份文件格式"))
        val viewModel = BackupViewModel(repository)

        viewModel.selectImportFile(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.showConfirmRestoreDialog).isFalse()
        assertThat(viewModel.uiState.value.message).contains("无效的备份文件")
        assertThat(viewModel.uiState.value.isError).isTrue()
    }

    @Test
    fun `confirm restore success updates state`() = runTest {
        coEvery { repository.validateBackup(context, uri) } returns Result.success(summary)
        coEvery { repository.importFromJson(context, uri) } returns Result.success(Unit)
        val viewModel = BackupViewModel(repository)

        viewModel.selectImportFile(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmRestore(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.message).isEqualTo("数据已成功恢复")
        assertThat(viewModel.uiState.value.isError).isFalse()
        assertThat(viewModel.uiState.value.showConfirmRestoreDialog).isFalse()
    }

    @Test
    fun `confirm restore failure updates state with error`() = runTest {
        coEvery { repository.validateBackup(context, uri) } returns Result.success(summary)
        coEvery { repository.importFromJson(context, uri) } returns
            Result.failure(Exception("数据库写入失败"))
        val viewModel = BackupViewModel(repository)

        viewModel.selectImportFile(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmRestore(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.message).contains("导入失败")
        assertThat(viewModel.uiState.value.message).contains("数据库写入失败")
        assertThat(viewModel.uiState.value.isError).isTrue()
    }

    @Test
    fun `dismiss confirm dialog clears metadata and pending uri`() = runTest {
        coEvery { repository.validateBackup(context, uri) } returns Result.success(summary)
        val viewModel = BackupViewModel(repository)

        viewModel.selectImportFile(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.dismissConfirmDialog()

        assertThat(viewModel.uiState.value.showConfirmRestoreDialog).isFalse()
        assertThat(viewModel.uiState.value.backupMetadata).isNull()
        assertThat(viewModel.uiState.value.pendingRestoreUri).isNull()
    }

    @Test
    fun `clear message resets message and error state`() = runTest {
        coEvery { repository.exportToJson(context, uri) } returns
            Result.failure(Exception("错误"))
        val viewModel = BackupViewModel(repository)

        viewModel.exportBackup(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.clearMessage()

        assertThat(viewModel.uiState.value.message).isNull()
        assertThat(viewModel.uiState.value.isError).isFalse()
    }

    @Test
    fun `confirm restore without pending uri is no-op`() = runTest {
        val viewModel = BackupViewModel(repository)

        viewModel.confirmRestore(context)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.message).isNull()
    }

    @Test
    fun `export invokes repository method`() = runTest {
        coEvery { repository.exportToJson(context, uri) } returns Result.success(Unit)
        val viewModel = BackupViewModel(repository)

        viewModel.exportBackup(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.exportToJson(context, uri) }
    }

    @Test
    fun `import validation invokes repository method`() = runTest {
        coEvery { repository.validateBackup(context, uri) } returns Result.success(summary)
        val viewModel = BackupViewModel(repository)

        viewModel.selectImportFile(context, uri)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.validateBackup(context, uri) }
    }
}
