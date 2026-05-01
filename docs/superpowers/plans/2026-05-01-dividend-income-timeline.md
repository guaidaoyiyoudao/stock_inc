# Dividend Income Timeline Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dividend income timeline tab to HomeScreen that auto-generates income records from existing dividend data and lets users manually correct or add records.

**Architecture:** New Room entity + DAO for `dividend_income_records`, a repository that handles auto-generation via diffing dividends vs existing records, a ViewModel managing year-based timeline state, and Compose components for the timeline UI. The HomeScreen gains a tab row to switch between the existing watchlist and the new income tab.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material Design 3, Room 2.6.1, Hilt 2.53.1, Coroutines 1.9.0

**Spec:** `docs/superpowers/specs/2026-05-01-dividend-income-timeline-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `app/src/main/java/com/stock/dividend/data/local/entity/DividendIncomeRecordEntity.kt` | Room entity for `dividend_income_records` table |
| `app/src/main/java/com/stock/dividend/data/local/dao/DividendIncomeRecordDao.kt` | CRUD queries for income records |
| `app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt` | Auto-generation diff logic, manual record CRUD, YoY calculation |
| `app/src/main/java/com/stock/dividend/viewmodel/DividendIncomeViewModel.kt` | UI state: selected year, records, summary, dialog state |
| `app/src/main/java/com/stock/dividend/ui/component/IncomeTimelineCard.kt` | Collapsible timeline item |
| `app/src/main/java/com/stock/dividend/ui/component/IncomeSummaryCard.kt` | Year summary with YoY comparison |
| `app/src/main/java/com/stock/dividend/ui/component/YearSelector.kt` | Horizontal year chip selector |
| `app/src/test/java/com/stock/dividend/data/repository/DividendIncomeRepositoryTest.kt` | Repository unit tests |
| `app/src/test/java/com/stock/dividend/viewmodel/DividendIncomeViewModelTest.kt` | ViewModel unit tests |

### Modified Files

| File | Change |
|------|--------|
| `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt` | Add entity, bump v4→v5, add migration |
| `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt` | Provide new DAO, register migration |
| `app/src/main/java/com/stock/dividend/data/local/dao/DividendDao.kt` | Add `getAllWithExDate()` query (Task 2) |
| `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt` | Add TabRow + income tab (Task 7) |

**Known spec deviations:**
- Per-share dividend amount and shares held in expanded view are deferred — the entity does not store `cashPerShare` and looking it up at display time adds complexity for marginal value. The expanded view shows ex-dividend date and note only.
- Compose tests for IncomeTimelineCard, IncomeSummaryCard, and YearSelector are deferred to keep scope manageable.

---

## Chunk 1: Data Layer

### Task 1: Entity + DAO + Database Migration

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/local/entity/DividendIncomeRecordEntity.kt`
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/DividendIncomeRecordDao.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt:14-50`
- Modify: `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt:28-39`

- [ ] **Step 1: Create DividendIncomeRecordEntity**

```kotlin
// app/src/main/java/com/stock/dividend/data/local/entity/DividendIncomeRecordEntity.kt
package com.stock.dividend.data.local.entity

import androidx.compose.runtime.Stable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Stable
@Entity(
    tableName = "dividend_income_records",
    foreignKeys = [
        ForeignKey(
            entity = StockEntity::class,
            parentColumns = ["code"],
            childColumns = ["stockCode"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("stockCode"), Index("year")]
)
data class DividendIncomeRecordEntity(
    @PrimaryKey
    val id: String,
    val stockCode: String? = null,
    val year: Int,
    val date: String,
    val amount: Double,
    val exDividendDate: String? = null,
    val source: String = "auto",
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: Create DividendIncomeRecordDao**

```kotlin
// app/src/main/java/com/stock/dividend/data/local/dao/DividendIncomeRecordDao.kt
package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DividendIncomeRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<DividendIncomeRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DividendIncomeRecordEntity)

    @Query("SELECT * FROM dividend_income_records WHERE year = :year ORDER BY date DESC")
    fun observeByYear(year: Int): Flow<List<DividendIncomeRecordEntity>>

    @Query("SELECT * FROM dividend_income_records WHERE source = 'auto'")
    suspend fun getAllAutoRecords(): List<DividendIncomeRecordEntity>

    @Query("UPDATE dividend_income_records SET source = 'manual', amount = :amount, note = :note, updatedAt = :updatedAt WHERE id = :id")
    suspend fun correctRecord(id: String, amount: Double, note: String?, updatedAt: Long)

    @Query("DELETE FROM dividend_income_records WHERE id = :id AND source = 'manual'")
    suspend fun deleteManualRecord(id: String): Int

    @Query("SELECT DISTINCT year FROM dividend_income_records ORDER BY year DESC")
    fun observeAvailableYears(): Flow<List<Int>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM dividend_income_records WHERE year = :year")
    fun observeTotalByYear(year: Int): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM dividend_income_records WHERE year = :year AND source = 'manual'")
    fun observeManualTotalByYear(year: Int): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM dividend_income_records WHERE year = :year AND source = 'auto'")
    fun observeAutoTotalByYear(year: Int): Flow<Double>

    @Query("SELECT COUNT(*) FROM dividend_income_records WHERE year = :year AND source = 'manual'")
    suspend fun getManualCountByYear(year: Int): Int

    @Query("SELECT COUNT(*) FROM dividend_income_records WHERE year = :year AND source = 'auto'")
    suspend fun getAutoCountByYear(year: Int): Int
}
```

- [ ] **Step 3: Update AppDatabase — add entity, bump version, add migration**

In `AppDatabase.kt`:

- Change `entities` list to include `DividendIncomeRecordEntity::class`
- Change `version = 4` to `version = 5`
- Add `abstract fun dividendIncomeRecordDao(): DividendIncomeRecordDao`
- Add `MIGRATION_4_5` companion object:

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dividend_income_records` (" +
                    "`id` TEXT NOT NULL PRIMARY KEY, " +
                    "`stockCode` TEXT NULLABLE, " +
                    "`year` INTEGER NOT NULL, " +
                    "`date` TEXT NOT NULL, " +
                    "`amount` REAL NOT NULL, " +
                    "`exDividendDate` TEXT NULLABLE, " +
                    "`source` TEXT NOT NULL DEFAULT 'auto', " +
                    "`note` TEXT NULLABLE, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`stockCode`) REFERENCES `stocks`(`code`) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dividend_income_records_stock_code` ON `dividend_income_records`(`stockCode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dividend_income_records_year` ON `dividend_income_records`(`year`)")
    }
}
```

- [ ] **Step 4: Update DatabaseModule — provide new DAO, register migration**

In `DatabaseModule.kt`:

- Add import for `DividendIncomeRecordDao`
- Add `AppDatabase.MIGRATION_4_5` to the `.addMigrations()` call
- Add provider method:

```kotlin
@Provides
fun provideDividendIncomeRecordDao(db: AppDatabase): DividendIncomeRecordDao = db.dividendIncomeRecordDao()
```

- [ ] **Step 5: Build and verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/entity/DividendIncomeRecordEntity.kt \
        app/src/main/java/com/stock/dividend/data/local/dao/DividendIncomeRecordDao.kt \
        app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt \
        app/src/main/java/com/stock/dividend/di/DatabaseModule.kt
git commit -m "feat: add dividend_income_records table with Room entity, DAO, and DB migration v4→v5"
```

---

## Chunk 2: Repository + Tests

### Task 2: DividendIncomeRepository

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt`
- Create: `app/src/test/java/com/stock/dividend/data/repository/DividendIncomeRepositoryTest.kt`

- [ ] **Step 1: Write failing tests for auto-generation logic**

```kotlin
// app/src/test/java/com/stock/dividend/data/repository/DividendIncomeRepositoryTest.kt
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.DividendEntity
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.StockEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DividendIncomeRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private val incomeRecordDao: DividendIncomeRecordDao = mockk(relaxed = true)
    private val dividendDao: DividendDao = mockk(relaxed = true)
    private val stockDao: StockDao = mockk(relaxed = true)

    private lateinit var repository: DividendIncomeRepository

    private val testStock = StockEntity(
        code = "sh.600000",
        name = "浦发银行",
        marketCode = "1",
        shares = 1000
    )

    @Before
    fun setUp() {
        repository = DividendIncomeRepository(incomeRecordDao, dividendDao, stockDao)
    }

    // --- Auto-generation tests ---

    @Test
    fun `generateMissingAutoRecords inserts records for dividends without existing auto records`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = "2024-07-10"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllAutoRecords() } returns emptyList()
        coEvery { stockDao.getByCode("sh.600000") } returns testStock

        repository.generateMissingAutoRecords()

        val recordsSlot = slot<List<DividendIncomeRecordEntity>>()
        coVerify { incomeRecordDao.insertAll(capture(recordsSlot)) }
        val records = recordsSlot.captured
        assertThat(records).hasSize(1)
        assertThat(records[0].id).isEqualTo("auto_sh.600000_2024-07-10")
        assertThat(records[0].amount).isWithin(0.001).of(246.0) // 0.246 * 1000
        assertThat(records[0].source).isEqualTo("auto")
        assertThat(records[0].year).isEqualTo(2024)
        assertThat(records[0].date).isEqualTo("2024-07-10")
    }

    @Test
    fun `generateMissingAutoRecords skips dividends with null exDividendDate`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = null
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllAutoRecords() } returns emptyList()

        repository.generateMissingAutoRecords()

        coVerify(exactly = 0) { incomeRecordDao.insertAll(any()) }
    }

    @Test
    fun `generateMissingAutoRecords generates zero-amount record when shares is zero`() = runTest {
        val zeroShareStock = testStock.copy(shares = 0)
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = "2024-07-10"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllAutoRecords() } returns emptyList()
        coEvery { stockDao.getByCode("sh.600000") } returns zeroShareStock

        repository.generateMissingAutoRecords()

        val recordsSlot = slot<List<DividendIncomeRecordEntity>>()
        coVerify { incomeRecordDao.insertAll(capture(recordsSlot)) }
        assertThat(recordsSlot.captured[0].amount).isWithin(0.001).of(0.0)
    }

    @Test
    fun `generateMissingAutoRecords does not insert when auto record already exists`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = "2024-07-10"
            )
        )
        val existingRecords = listOf(
            DividendIncomeRecordEntity(
                id = "auto_sh.600000_2024-07-10",
                stockCode = "sh.600000",
                year = 2024,
                date = "2024-07-10",
                amount = 246.0,
                source = "auto"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllAutoRecords() } returns existingRecords

        repository.generateMissingAutoRecords()

        coVerify(exactly = 0) { incomeRecordDao.insertAll(any()) }
    }

    @Test
    fun `generateMissingAutoRecords handles multiple dividends per stock`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.600000_2024-12-31",
                stockCode = "sh.600000",
                reportDate = "2024-12-31",
                cashPerShare = 0.246,
                exDividendDate = "2024-07-10"
            ),
            DividendEntity(
                id = "sh.600000_2024-06-30",
                stockCode = "sh.600000",
                reportDate = "2024-06-30",
                cashPerShare = 0.100,
                exDividendDate = "2024-01-15"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllAutoRecords() } returns emptyList()
        coEvery { stockDao.getByCode("sh.600000") } returns testStock

        repository.generateMissingAutoRecords()

        val recordsSlot = slot<List<DividendIncomeRecordEntity>>()
        coVerify { incomeRecordDao.insertAll(capture(recordsSlot)) }
        assertThat(recordsSlot.captured).hasSize(2)
    }

    @Test
    fun `generateMissingAutoRecords skips when stock not found in stockDao`() = runTest {
        val dividends = listOf(
            DividendEntity(
                id = "sh.999999_2024-12-31",
                stockCode = "sh.999999",
                reportDate = "2024-12-31",
                cashPerShare = 0.1,
                exDividendDate = "2024-07-10"
            )
        )
        coEvery { dividendDao.getAllWithExDate() } returns dividends
        coEvery { incomeRecordDao.getAllAutoRecords() } returns emptyList()
        coEvery { stockDao.getByCode("sh.999999") } returns null

        repository.generateMissingAutoRecords()

        coVerify(exactly = 0) { incomeRecordDao.insertAll(any()) }
    }

    // --- Correction tests ---

    @Test
    fun `correctRecord updates source to manual and amount`() = runTest {
        coEvery { incomeRecordDao.correctRecord(any(), any(), any(), any()) } returns Unit

        repository.correctRecord("auto_sh.600000_2024-07-10", 300.0, "实际到账300")

        coVerify {
            incomeRecordDao.correctRecord(
                "auto_sh.600000_2024-07-10",
                300.0,
                "实际到账300",
                any()
            )
        }
    }

    // --- Manual add tests ---

    @Test
    fun `addManualRecord creates record with correct fields`() = runTest {
        coEvery { incomeRecordDao.insert(any()) } returns Unit

        repository.addManualRecord(
            date = "2025-03-15",
            amount = 500.0,
            stockCode = "sh.600000",
            note = "港股通分红"
        )

        val recordSlot = slot<DividendIncomeRecordEntity>()
        coVerify { incomeRecordDao.insert(capture(recordSlot)) }
        val record = recordSlot.captured
        assertThat(record.source).isEqualTo("manual")
        assertThat(record.date).isEqualTo("2025-03-15")
        assertThat(record.amount).isWithin(0.001).of(500.0)
        assertThat(record.stockCode).isEqualTo("sh.600000")
        assertThat(record.note).isEqualTo("港股通分红")
        assertThat(record.year).isEqualTo(2025)
        assertThat(record.id).startsWith("manual_")
    }

    @Test
    fun `addManualRecord without stockCode creates null stockCode record`() = runTest {
        coEvery { incomeRecordDao.insert(any()) } returns Unit

        repository.addManualRecord(
            date = "2025-03-15",
            amount = 200.0,
            stockCode = null,
            note = null
        )

        val recordSlot = slot<DividendIncomeRecordEntity>()
        coVerify { incomeRecordDao.insert(capture(recordSlot)) }
        assertThat(recordSlot.captured.stockCode).isNull()
    }

    // --- Delete tests ---

    @Test
    fun `deleteManualRecord delegates to DAO`() = runTest {
        coEvery { incomeRecordDao.deleteManualRecord(any()) } returns 1

        repository.deleteManualRecord("manual_12345")

        coVerify { incomeRecordDao.deleteManualRecord("manual_12345") }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.stock.dividend.data.repository.DividendIncomeRepositoryTest" 2>&1 | tail -20`
Expected: Compilation errors — `DividendIncomeRepository` and `DividendDao.getAllWithExDate` do not exist yet.

- [ ] **Step 3: Add `getAllWithExDate` query to DividendDao**

Add to `DividendDao.kt`:

```kotlin
@Query("SELECT * FROM dividends WHERE exDividendDate IS NOT NULL")
suspend fun getAllWithExDate(): List<DividendEntity>
```

- [ ] **Step 4: Implement DividendIncomeRepository**

```kotlin
// app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt
package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.DividendDao
import com.stock.dividend.data.local.dao.DividendIncomeRecordDao
import com.stock.dividend.data.local.dao.StockDao
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DividendIncomeRepository @Inject constructor(
    private val incomeRecordDao: DividendIncomeRecordDao,
    private val dividendDao: DividendDao,
    private val stockDao: StockDao
) {
    suspend fun generateMissingAutoRecords() {
        val allDividends = dividendDao.getAllWithExDate()
        val existingAutoRecords = incomeRecordDao.getAllAutoRecords()
        val existingAutoIds = existingAutoRecords.map { it.id }.toSet()

        val newRecords = mutableListOf<DividendIncomeRecordEntity>()
        for (dividend in allDividends) {
            val exDate = dividend.exDividendDate ?: continue
            val autoId = "auto_${dividend.stockCode}_${exDate}"
            if (autoId in existingAutoIds) continue

            val stock = stockDao.getByCode(dividend.stockCode) ?: continue
            val shares = stock.shares
            val amount = dividend.cashPerShare * shares

            newRecords.add(
                DividendIncomeRecordEntity(
                    id = autoId,
                    stockCode = dividend.stockCode,
                    year = exDate.substring(0, 4).toInt(),
                    date = exDate,
                    amount = amount,
                    exDividendDate = exDate,
                    source = "auto"
                )
            )
        }

        if (newRecords.isNotEmpty()) {
            incomeRecordDao.insertAll(newRecords)
        }
    }

    fun observeByYear(year: Int): Flow<List<DividendIncomeRecordEntity>> =
        incomeRecordDao.observeByYear(year)

    fun observeAvailableYears(): Flow<List<Int>> =
        incomeRecordDao.observeAvailableYears()

    fun observeTotalByYear(year: Int): Flow<Double> =
        incomeRecordDao.observeTotalByYear(year)

    suspend fun getManualCountByYear(year: Int): Int =
        incomeRecordDao.getManualCountByYear(year)

    suspend fun getAutoCountByYear(year: Int): Int =
        incomeRecordDao.getAutoCountByYear(year)

    suspend fun correctRecord(id: String, amount: Double, note: String?) {
        incomeRecordDao.correctRecord(id, amount, note, System.currentTimeMillis())
    }

    suspend fun addManualRecord(date: String, amount: Double, stockCode: String?, note: String?) {
        val year = date.substring(0, 4).toInt()
        val record = DividendIncomeRecordEntity(
            id = "manual_${System.currentTimeMillis()}",
            stockCode = stockCode,
            year = year,
            date = date,
            amount = amount,
            source = "manual",
            note = note
        )
        incomeRecordDao.insert(record)
    }

    suspend fun deleteManualRecord(id: String) {
        incomeRecordDao.deleteManualRecord(id)
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.stock.dividend.data.repository.DividendIncomeRepositoryTest" 2>&1 | tail -20`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/repository/DividendIncomeRepository.kt \
        app/src/main/java/com/stock/dividend/data/local/dao/DividendDao.kt \
        app/src/test/java/com/stock/dividend/data/repository/DividendIncomeRepositoryTest.kt
git commit -m "feat: add DividendIncomeRepository with auto-generation diff logic and manual CRUD"
```

---

## Chunk 3: ViewModel

### Task 3: DividendIncomeViewModel

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/DividendIncomeViewModel.kt`
- Create: `app/src/test/java/com/stock/dividend/viewmodel/DividendIncomeViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

```kotlin
// app/src/test/java/com/stock/dividend/viewmodel/DividendIncomeViewModelTest.kt
package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.DividendIncomeRecordEntity
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.StockRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DividendIncomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val incomeRepository: DividendIncomeRepository = mockk(relaxed = true)
    private val stockRepository: StockRepository = mockk(relaxed = true)

    private val recordsFlow = MutableStateFlow<List<DividendIncomeRecordEntity>>(emptyList())
    private val yearsFlow = MutableStateFlow<List<Int>>(emptyList())
    private val totalFlow = MutableStateFlow(0.0)
    private val stocksFlow = MutableStateFlow<List<StockEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { incomeRepository.observeByYear(any()) } returns recordsFlow
        every { incomeRepository.observeAvailableYears() } returns yearsFlow
        every { incomeRepository.observeTotalByYear(any()) } returns totalFlow
        every { stockRepository.observeAllStocks() } returns stocksFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init triggers auto-generation`() = runTest {
        val viewModel = DividendIncomeViewModel(incomeRepository, stockRepository)
        advanceUntilIdle()

        coVerify { incomeRepository.generateMissingAutoRecords() }
    }

    @Test
    fun `selectYear updates selected year`() = runTest {
        val viewModel = DividendIncomeViewModel(incomeRepository, stockRepository)
        advanceUntilIdle()

        viewModel.selectYear(2024)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedYear).isEqualTo(2024)
    }

    @Test
    fun `addManualRecord calls repository and clears dialog`() = runTest {
        coEvery { incomeRepository.addManualRecord(any(), any(), any(), any()) } returns Unit

        val viewModel = DividendIncomeViewModel(incomeRepository, stockRepository)
        advanceUntilIdle()

        viewModel.showAddDialog()
        assertThat(viewModel.uiState.value.showAddDialog).isTrue()

        viewModel.addManualRecord("2025-03-15", 500.0, "sh.600000", "test")
        advanceUntilIdle()

        coVerify { incomeRepository.addManualRecord("2025-03-15", 500.0, "sh.600000", "test") }
        assertThat(viewModel.uiState.value.showAddDialog).isFalse()
    }

    @Test
    fun `correctRecord calls repository and clears dialog`() = runTest {
        coEvery { incomeRepository.correctRecord(any(), any(), any()) } returns Unit

        val viewModel = DividendIncomeViewModel(incomeRepository, stockRepository)
        advanceUntilIdle()

        viewModel.showCorrectDialog("auto_sh.600000_2024-07-10", 246.0)
        assertThat(viewModel.uiState.value.showCorrectDialog).isTrue()

        viewModel.correctRecord("auto_sh.600000_2024-07-10", 300.0, "adjusted")
        advanceUntilIdle()

        coVerify { incomeRepository.correctRecord("auto_sh.600000_2024-07-10", 300.0, "adjusted") }
        assertThat(viewModel.uiState.value.showCorrectDialog).isFalse()
    }

    @Test
    fun `deleteManualRecord calls repository`() = runTest {
        coEvery { incomeRepository.deleteManualRecord(any()) } returns Unit

        val viewModel = DividendIncomeViewModel(incomeRepository, stockRepository)
        advanceUntilIdle()

        viewModel.deleteManualRecord("manual_12345")
        advanceUntilIdle()

        coVerify { incomeRepository.deleteManualRecord("manual_12345") }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.stock.dividend.viewmodel.DividendIncomeViewModelTest" 2>&1 | tail -20`
Expected: Compilation error — `DividendIncomeViewModel` does not exist.

- [ ] **Step 3: Implement DividendIncomeViewModel**

```kotlin
// app/src/main/java/com/stock/dividend/viewmodel/DividendIncomeViewModel.kt
package com.stock.dividend.viewmodel

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.StockEntity
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@Stable
data class DividendIncomeUiState(
    val selectedYear: Int = LocalDate.now().year,
    val records: List<DividendIncomeRecordWithStock> = emptyList(),
    val availableYears: List<Int> = emptyList(),
    val yearlyTotal: Double = 0.0,
    val manualCount: Int = 0,
    val autoCount: Int = 0,
    val prevYearTotal: Double? = null,
    val stocks: List<StockEntity> = emptyList(),
    val showAddDialog: Boolean = false,
    val showCorrectDialog: Boolean = false,
    val correctTargetId: String = "",
    val correctCurrentAmount: Double = 0.0,
    val isLoading: Boolean = true
)

@Stable
data class DividendIncomeRecordWithStock(
    val record: com.stock.dividend.data.local.entity.DividendIncomeRecordEntity,
    val stockName: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DividendIncomeViewModel @Inject constructor(
    private val incomeRepository: DividendIncomeRepository,
    private val stockRepository: StockRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DividendIncomeUiState())
    val uiState: StateFlow<DividendIncomeUiState> = _uiState.asStateFlow()

    private val _selectedYear = MutableStateFlow(LocalDate.now().year)

    private val stocksFlow = stockRepository.observeAllStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Auto-generate missing records on init
        viewModelScope.launch {
            incomeRepository.generateMissingAutoRecords()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }

        // Observe available years
        viewModelScope.launch {
            incomeRepository.observeAvailableYears().collect { years ->
                _uiState.value = _uiState.value.copy(availableYears = years)
            }
        }

        // Observe records for selected year + stock names
        viewModelScope.launch {
            combine(
                _selectedYear.flatMapLatest { year ->
                    incomeRepository.observeByYear(year)
                },
                stocksFlow
            ) { records, stocks ->
                val stockMap = stocks.associateBy { it.code }
                records.map { record ->
                    DividendIncomeRecordWithStock(
                        record = record,
                        stockName = stockMap[record.stockCode]?.name
                    )
                }
            }.collect { recordsWithStock ->
                val manualCount = recordsWithStock.count { it.record.source == "manual" }
                val autoCount = recordsWithStock.count { it.record.source == "auto" }
                _uiState.value = _uiState.value.copy(
                    records = recordsWithStock,
                    manualCount = manualCount,
                    autoCount = autoCount
                )
            }
        }

        // Observe current year total
        viewModelScope.launch {
            _selectedYear.flatMapLatest { year ->
                incomeRepository.observeTotalByYear(year)
            }.collect { total ->
                _uiState.value = _uiState.value.copy(yearlyTotal = total)
            }
        }

        // Observe previous year total for YoY comparison
        viewModelScope.launch {
            _selectedYear.flatMapLatest { year ->
                incomeRepository.observeTotalByYear(year - 1)
            }.collect { prevTotal ->
                _uiState.value = _uiState.value.copy(
                    prevYearTotal = if (prevTotal == 0.0) null else prevTotal
                )
            }
        }

        // Observe stocks for stock selector
        viewModelScope.launch {
            stocksFlow.collect { stocks ->
                _uiState.value = _uiState.value.copy(stocks = stocks)
            }
        }
    }

    fun selectYear(year: Int) {
        _selectedYear.value = year
        _uiState.value = _uiState.value.copy(selectedYear = year)
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true)
    }

    fun dismissAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun addManualRecord(date: String, amount: Double, stockCode: String?, note: String?) {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
        viewModelScope.launch {
            incomeRepository.addManualRecord(date, amount, stockCode, note)
        }
    }

    fun showCorrectDialog(id: String, currentAmount: Double) {
        _uiState.value = _uiState.value.copy(
            showCorrectDialog = true,
            correctTargetId = id,
            correctCurrentAmount = currentAmount
        )
    }

    fun dismissCorrectDialog() {
        _uiState.value = _uiState.value.copy(showCorrectDialog = false)
    }

    fun correctRecord(id: String, amount: Double, note: String?) {
        _uiState.value = _uiState.value.copy(showCorrectDialog = false)
        viewModelScope.launch {
            incomeRepository.correctRecord(id, amount, note)
        }
    }

    fun deleteManualRecord(id: String) {
        viewModelScope.launch {
            incomeRepository.deleteManualRecord(id)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.stock.dividend.viewmodel.DividendIncomeViewModelTest" 2>&1 | tail -20`
Expected: All tests PASS

- [ ] **Step 5: Run all existing tests to verify no regressions**

Run: `./gradlew test 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/DividendIncomeViewModel.kt \
        app/src/test/java/com/stock/dividend/viewmodel/DividendIncomeViewModelTest.kt
git commit -m "feat: add DividendIncomeViewModel with year selection, correction, and add/delete flows"
```

---

## Chunk 4: UI Components

### Task 4: IncomeTimelineCard Component

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/component/IncomeTimelineCard.kt`

- [ ] **Step 1: Create IncomeTimelineCard**

```kotlin
// app/src/main/java/com/stock/dividend/ui/component/IncomeTimelineCard.kt
package com.stock.dividend.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun IncomeTimelineCard(
    date: String,
    stockName: String?,
    amount: Double,
    source: String,
    exDividendDate: String? = null,
    note: String? = null,
    onCorrect: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val isManual = source == "manual"
    val displayDate = if (date.length >= 10) date.substring(5, 10) else date

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Collapsed row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Date
                    Text(
                        text = displayDate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Stock name
                    Text(
                        text = stockName ?: "其他收入",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Source chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isManual) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isManual) "实际" else "推算",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isManual) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Amount
                Text(
                    text = "¥%.2f".format(amount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Expanded details
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    if (exDividendDate != null) {
                        DetailRow("除权除息日", exDividendDate)
                    }
                    if (note != null) {
                        DetailRow("备注", note)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!isManual && onCorrect != null) {
                            TextButton(onClick = onCorrect) {
                                Text("修正金额", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (isManual && onEdit != null) {
                            TextButton(onClick = onEdit) {
                                Text("编辑", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (isManual && onDelete != null) {
                            TextButton(onClick = onDelete) {
                                Text(
                                    "删除",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/component/IncomeTimelineCard.kt
git commit -m "feat: add IncomeTimelineCard component with expand/collapse and action buttons"
```

### Task 5: IncomeSummaryCard Component

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/component/IncomeSummaryCard.kt`

- [ ] **Step 1: Create IncomeSummaryCard**

```kotlin
// app/src/main/java/com/stock/dividend/ui/component/IncomeSummaryCard.kt
package com.stock.dividend.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun IncomeSummaryCard(
    year: Int,
    totalAmount: Double,
    prevYearTotal: Double?,
    manualCount: Int,
    autoCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Text(
                text = "${year}年股息收入",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("¥ ")
                    }
                    append("%.2f".format(totalAmount))
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // YoY comparison
                Text(
                    text = when {
                        prevYearTotal == null -> "首年记录"
                        prevYearTotal == 0.0 && totalAmount > 0 -> "首年有收入"
                        prevYearTotal > 0 -> {
                            val change = ((totalAmount - prevYearTotal) / prevYearTotal) * 100
                            if (change >= 0) "较去年 ↑%.1f%%".format(change)
                            else "较去年 ↓%.1f%%".format(-change)
                        }
                        else -> "较去年 —"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Source breakdown
                Text(
                    text = buildString {
                        if (manualCount > 0) append("${manualCount} 笔实际")
                        if (manualCount > 0 && autoCount > 0) append(" / ")
                        if (autoCount > 0) append("${autoCount} 笔推算")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/component/IncomeSummaryCard.kt
git commit -m "feat: add IncomeSummaryCard with YoY comparison and source breakdown"
```

### Task 6: YearSelector Component

**Files:**
- Create: `app/src/main/java/com/stock/dividend/ui/component/YearSelector.kt`

- [ ] **Step 1: Create YearSelector**

```kotlin
// app/src/main/java/com/stock/dividend/ui/component/YearSelector.kt
package com.stock.dividend.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearSelector(
    years: List<Int>,
    selectedYear: Int,
    onYearSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        years.forEach { year ->
            FilterChip(
                selected = year == selectedYear,
                onClick = { onYearSelected(year) },
                label = {
                    Text(
                        text = "${year}年",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (year == selectedYear) FontWeight.Bold else FontWeight.Normal
                    )
                },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
```

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/component/YearSelector.kt
git commit -m "feat: add YearSelector horizontal chip component"
```

---

## Chunk 5: Screen Integration

### Task 7: Integrate Income Tab into HomeScreen

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt`

This is the largest change — wrapping the existing HomeScreen content in a tab row and adding the income tab content. The approach:

1. Add `TabRow` at the top of the Scaffold content area
2. Wrap existing content in `if (selectedTabIndex == 0) { ... }`
3. Add income tab content in `if (selectedTabIndex == 1) { ... }`
4. The income tab uses `DividendIncomeViewModel` (separate from `HomeViewModel`)
5. Income tab includes: YearSelector, IncomeSummaryCard, LazyColumn of IncomeTimelineCards, and dialogs

- [ ] **Step 1: Update HomeScreen with tabs and income tab content**

Key structural changes to `HomeScreen.kt`:

**New imports needed:**
```kotlin
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.stock.dividend.ui.component.IncomeTimelineCard
import com.stock.dividend.ui.component.IncomeSummaryCard
import com.stock.dividend.ui.component.YearSelector
import com.stock.dividend.viewmodel.DividendIncomeViewModel
```

**Top of composable — add tab state + income ViewModel:**
```kotlin
var selectedTabIndex by remember { mutableStateOf(0) }
val incomeViewModel: DividendIncomeViewModel = hiltViewModel()
val incomeState by incomeViewModel.uiState.collectAsStateWithLifecycle()
```

**Scaffold layout — add TabRow below TopAppBar, wrap content in tab switch:**

Inside `Scaffold`, between the `TopAppBar` and the content `padding` lambda:
- Add `TabRow(selectedTabIndex = selectedTabIndex)` with two `Tab` composables: "关注列表" (index 0) and "股息收入" (index 1)
- `onTabSelected = { selectedTabIndex = it }`

Content area becomes:
```kotlin
when (selectedTabIndex) {
    0 -> { /* existing PullToRefreshBox + LazyColumn content */ }
    1 -> { IncomeTabContent(incomeState, incomeViewModel) }
}
```

**FAB changes:**
- Tab 0: existing "添加股票" FAB (only when stocks.isNotEmpty)
- Tab 1: "添加收入" FAB that calls `incomeViewModel.showAddDialog()`

**IncomeTabContent private composable:**
```kotlin
@Composable
private fun IncomeTabContent(
    state: DividendIncomeUiState,
    viewModel: DividendIncomeViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Year selector
        YearSelector(
            years = state.availableYears.ifEmpty { listOf(state.selectedYear) },
            selectedYear = state.selectedYear,
            onYearSelected = { viewModel.selectYear(it) }
        )

        // Summary card
        IncomeSummaryCard(
            year = state.selectedYear,
            totalAmount = state.yearlyTotal,
            prevYearTotal = state.prevYearTotal,
            manualCount = state.manualCount,
            autoCount = state.autoCount
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Timeline list or empty state
        if (state.records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无股息收入记录", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("分红到账后会自动记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = state.records, key = { it.record.id }) { item ->
                    IncomeTimelineCard(
                        date = item.record.date,
                        stockName = item.stockName,
                        amount = item.record.amount,
                        source = item.record.source,
                        exDividendDate = item.record.exDividendDate,
                        note = item.record.note,
                        onCorrect = {
                            viewModel.showCorrectDialog(item.record.id, item.record.amount)
                        },
                        onEdit = {
                            viewModel.showCorrectDialog(item.record.id, item.record.amount)
                        },
                        onDelete = {
                            viewModel.deleteManualRecord(item.record.id)
                        }
                    )
                }
            }
        }
    }
}
```

**Add dialog composables** for "添加收入" and "修正金额":

Add Income Dialog — collects date (DatePicker), amount (OutlinedTextField), optional stock (dropdown from `incomeState.stocks`), optional note. On confirm calls `viewModel.addManualRecord(...)`.

Correct Dialog — shows amount input (pre-filled with `correctCurrentAmount`) and note input. On confirm calls `viewModel.correctRecord(id, amount, note)`.

- [ ] **Step 2: Build and verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run all tests to verify no regressions**

Run: `./gradlew test 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt
git commit -m "feat: add income timeline tab to HomeScreen with year selector and dialogs"
```

---

## Chunk 6: Final Integration + Smoke Test

### Task 8: Final build and verification

- [ ] **Step 1: Full debug build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run all tests**

Run: `./gradlew test 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 3: Manual smoke test checklist**

Install the debug APK on a device/emulator and verify:

- [ ] HomeScreen shows two tabs: "关注列表" and "股息收入"
- [ ] Default tab is "关注列表" — existing functionality works unchanged
- [ ] Switch to "股息收入" tab — shows year selector and timeline
- [ ] Auto-generated records appear for stocks with dividend history
- [ ] Tapping a record expands to show details
- [ ] "修正金额" button works on auto records
- [ ] "添加收入" FAB opens add dialog
- [ ] Manual records can be deleted
- [ ] Year switching works correctly
- [ ] IncomeSummaryCard shows correct totals and source breakdown
