# Living Expense Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a living expense coverage page where forecast annual dividend income covers user-defined monthly/yearly expenses one by one in user-controlled order.

**Architecture:** Add a small Room-backed living expense domain, keep coverage allocation as a pure Kotlin calculator, and expose page state through the existing `ExpenseCoverageViewModel`. Reuse the existing `ExpenseCoverageScreen` route, replacing its single FIRE target content with a summary, expense queue, and add/edit dialog.

**Tech Stack:** Kotlin 2.0.21, Java 17, Room, Hilt, Coroutines Flow, Jetpack Compose Material 3, JUnit, MockK, Truth, coroutine test.

---

## File Structure

- Create `app/src/main/java/com/stock/dividend/data/local/entity/LivingExpenseItemEntity.kt`: Room entity and period constants.
- Create `app/src/main/java/com/stock/dividend/data/local/dao/LivingExpenseItemDao.kt`: ordered reads and writes for expense items.
- Create `app/src/main/java/com/stock/dividend/data/repository/LivingExpenseRepository.kt`: insert validation, next sort order, update/delete, move up/down.
- Modify `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt`: add entity, DAO, version 8 migration.
- Modify `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt`: add migration and DAO provider.
- Create `app/src/main/java/com/stock/dividend/viewmodel/ExpenseCoverageCalculator.kt`: pure annualization and allocation logic.
- Modify `app/src/main/java/com/stock/dividend/viewmodel/ExpenseCoverageViewModel.kt`: consume forecast income and expenses; expose dialog/order actions.
- Modify `app/src/main/java/com/stock/dividend/ui/screen/ExpenseCoverageScreen.kt`: new summary, expense rows, add/edit dialog.
- Modify `app/src/main/res/values/strings.xml`: user-facing labels.
- Create `app/src/test/java/com/stock/dividend/viewmodel/ExpenseCoverageCalculatorTest.kt`.
- Create `app/src/test/java/com/stock/dividend/data/repository/LivingExpenseRepositoryTest.kt`.
- Create or replace `app/src/test/java/com/stock/dividend/viewmodel/ExpenseCoverageViewModelTest.kt`.

---

### Task 1: Pure Coverage Calculator

**Files:**
- Create: `app/src/main/java/com/stock/dividend/viewmodel/ExpenseCoverageCalculator.kt`
- Test: `app/src/test/java/com/stock/dividend/viewmodel/ExpenseCoverageCalculatorTest.kt`

- [ ] **Step 1: Write the failing calculator test**

Create `app/src/test/java/com/stock/dividend/viewmodel/ExpenseCoverageCalculatorTest.kt`:

```kotlin
package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpenseCoverageCalculatorTest {

    @Test
    fun `monthly item annualizes by multiplying by twelve`() {
        val item = CoverageExpenseInput(
            id = 1,
            name = "房租",
            amount = 3000.0,
            period = ExpensePeriod.MONTHLY,
            sortOrder = 0
        )

        val result = ExpenseCoverageCalculator.calculate(50_000.0, listOf(item))

        assertThat(result.totalAnnualExpense).isEqualTo(36_000.0)
        assertThat(result.rows.single().annualAmount).isEqualTo(36_000.0)
    }

    @Test
    fun `yearly item keeps entered amount`() {
        val item = CoverageExpenseInput(
            id = 1,
            name = "保险",
            amount = 6000.0,
            period = ExpensePeriod.YEARLY,
            sortOrder = 0
        )

        val result = ExpenseCoverageCalculator.calculate(50_000.0, listOf(item))

        assertThat(result.totalAnnualExpense).isEqualTo(6000.0)
        assertThat(result.rows.single().annualAmount).isEqualTo(6000.0)
    }

    @Test
    fun `items are covered in sort order with partial row before uncovered rows`() {
        val items = listOf(
            CoverageExpenseInput(2, "餐饮", 18_000.0, ExpensePeriod.YEARLY, sortOrder = 1),
            CoverageExpenseInput(1, "房租", 3000.0, ExpensePeriod.MONTHLY, sortOrder = 0),
            CoverageExpenseInput(3, "交通", 6000.0, ExpensePeriod.YEARLY, sortOrder = 2)
        )

        val result = ExpenseCoverageCalculator.calculate(45_000.0, items)

        assertThat(result.coverageRatio).isWithin(0.0001).of(45_000.0 / 60_000.0)
        assertThat(result.coveredItemCount).isEqualTo(1)
        assertThat(result.currentCoveringItemName).isEqualTo("餐饮")
        assertThat(result.remainingSurplus).isEqualTo(0.0)

        assertThat(result.rows.map { it.name }).containsExactly("房租", "餐饮", "交通").inOrder()
        assertThat(result.rows[0].status).isEqualTo(CoverageStatus.COVERED)
        assertThat(result.rows[0].coveredAmount).isEqualTo(36_000.0)
        assertThat(result.rows[1].status).isEqualTo(CoverageStatus.PARTIAL)
        assertThat(result.rows[1].coveredAmount).isEqualTo(9000.0)
        assertThat(result.rows[1].gapAmount).isEqualTo(9000.0)
        assertThat(result.rows[2].status).isEqualTo(CoverageStatus.UNCOVERED)
    }

    @Test
    fun `surplus is exposed when forecast covers all expenses`() {
        val items = listOf(
            CoverageExpenseInput(1, "房租", 12_000.0, ExpensePeriod.YEARLY, 0)
        )

        val result = ExpenseCoverageCalculator.calculate(20_000.0, items)

        assertThat(result.coverageRatio).isEqualTo(1.0)
        assertThat(result.coveredItemCount).isEqualTo(1)
        assertThat(result.currentCoveringItemName).isNull()
        assertThat(result.remainingSurplus).isEqualTo(8000.0)
        assertThat(result.rows.single().status).isEqualTo(CoverageStatus.COVERED)
    }

    @Test
    fun `no expenses returns empty result and zero ratio`() {
        val result = ExpenseCoverageCalculator.calculate(20_000.0, emptyList())

        assertThat(result.totalAnnualExpense).isEqualTo(0.0)
        assertThat(result.coverageRatio).isEqualTo(0.0)
        assertThat(result.coveredItemCount).isEqualTo(0)
        assertThat(result.rows).isEmpty()
    }
}
```

- [ ] **Step 2: Run the calculator test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.viewmodel.ExpenseCoverageCalculatorTest"
```

Expected: FAIL because `CoverageExpenseInput`, `ExpensePeriod`, `ExpenseCoverageCalculator`, and related result types do not exist.

- [ ] **Step 3: Implement the minimal calculator**

Create `app/src/main/java/com/stock/dividend/viewmodel/ExpenseCoverageCalculator.kt`:

```kotlin
package com.stock.dividend.viewmodel

enum class ExpensePeriod {
    MONTHLY,
    YEARLY
}

enum class CoverageStatus {
    COVERED,
    PARTIAL,
    UNCOVERED
}

data class CoverageExpenseInput(
    val id: Long,
    val name: String,
    val amount: Double,
    val period: ExpensePeriod,
    val sortOrder: Int
)

data class ExpenseCoverageRow(
    val id: Long,
    val name: String,
    val amount: Double,
    val period: ExpensePeriod,
    val annualAmount: Double,
    val coveredAmount: Double,
    val gapAmount: Double,
    val status: CoverageStatus,
    val sortOrder: Int
)

data class ExpenseCoverageCalculation(
    val forecastAnnualDividendIncome: Double,
    val totalAnnualExpense: Double,
    val coverageRatio: Double,
    val coveredItemCount: Int,
    val currentCoveringItemName: String?,
    val remainingSurplus: Double,
    val rows: List<ExpenseCoverageRow>
)

object ExpenseCoverageCalculator {
    fun calculate(
        forecastAnnualDividendIncome: Double,
        items: List<CoverageExpenseInput>
    ): ExpenseCoverageCalculation {
        var remainingIncome = forecastAnnualDividendIncome.coerceAtLeast(0.0)
        var currentCoveringItemName: String? = null

        val rows = items
            .sortedWith(compareBy<CoverageExpenseInput> { it.sortOrder }.thenBy { it.id })
            .map { item ->
                val annualAmount = item.annualAmount()
                val coveredAmount = remainingIncome.coerceAtMost(annualAmount)
                val gapAmount = annualAmount - coveredAmount
                val status = when {
                    annualAmount <= 0.0 -> CoverageStatus.COVERED
                    coveredAmount >= annualAmount -> CoverageStatus.COVERED
                    coveredAmount > 0.0 -> CoverageStatus.PARTIAL
                    else -> CoverageStatus.UNCOVERED
                }
                if (status == CoverageStatus.PARTIAL && currentCoveringItemName == null) {
                    currentCoveringItemName = item.name
                }
                remainingIncome = (remainingIncome - coveredAmount).coerceAtLeast(0.0)
                ExpenseCoverageRow(
                    id = item.id,
                    name = item.name,
                    amount = item.amount,
                    period = item.period,
                    annualAmount = annualAmount,
                    coveredAmount = coveredAmount,
                    gapAmount = gapAmount,
                    status = status,
                    sortOrder = item.sortOrder
                )
            }

        val totalAnnualExpense = rows.sumOf { it.annualAmount }
        val coveredItemCount = rows.count { it.status == CoverageStatus.COVERED }
        val ratio = if (totalAnnualExpense > 0.0) {
            (forecastAnnualDividendIncome / totalAnnualExpense).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        return ExpenseCoverageCalculation(
            forecastAnnualDividendIncome = forecastAnnualDividendIncome,
            totalAnnualExpense = totalAnnualExpense,
            coverageRatio = ratio,
            coveredItemCount = coveredItemCount,
            currentCoveringItemName = currentCoveringItemName,
            remainingSurplus = remainingIncome,
            rows = rows
        )
    }

    private fun CoverageExpenseInput.annualAmount(): Double =
        when (period) {
            ExpensePeriod.MONTHLY -> amount * 12
            ExpensePeriod.YEARLY -> amount
        }
}
```

- [ ] **Step 4: Run the calculator test to verify it passes**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.viewmodel.ExpenseCoverageCalculatorTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/ExpenseCoverageCalculator.kt app/src/test/java/com/stock/dividend/viewmodel/ExpenseCoverageCalculatorTest.kt
git commit -m "Add living expense coverage calculator"
```

---

### Task 2: Room Entity, DAO, Repository, And Migration

**Files:**
- Create: `app/src/main/java/com/stock/dividend/data/local/entity/LivingExpenseItemEntity.kt`
- Create: `app/src/main/java/com/stock/dividend/data/local/dao/LivingExpenseItemDao.kt`
- Create: `app/src/main/java/com/stock/dividend/data/repository/LivingExpenseRepository.kt`
- Modify: `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt`
- Test: `app/src/test/java/com/stock/dividend/data/repository/LivingExpenseRepositoryTest.kt`

- [ ] **Step 1: Write the failing repository test**

Create `app/src/test/java/com/stock/dividend/data/repository/LivingExpenseRepositoryTest.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LivingExpenseRepositoryTest {
    private val dao: LivingExpenseItemDao = mockk(relaxed = true)
    private val repository = LivingExpenseRepository(dao)

    @Test
    fun `addExpense assigns next sort order`() = runTest {
        val itemSlot = slot<LivingExpenseItemEntity>()
        coEvery { dao.getMaxSortOrder() } returns 4
        coEvery { dao.insert(capture(itemSlot)) } returns 10L

        repository.addExpense("房租", 3000.0, "MONTHLY")

        assertThat(itemSlot.captured.name).isEqualTo("房租")
        assertThat(itemSlot.captured.amount).isEqualTo(3000.0)
        assertThat(itemSlot.captured.period).isEqualTo("MONTHLY")
        assertThat(itemSlot.captured.sortOrder).isEqualTo(5)
    }

    @Test
    fun `addExpense uses zero sort order when table is empty`() = runTest {
        val itemSlot = slot<LivingExpenseItemEntity>()
        coEvery { dao.getMaxSortOrder() } returns null
        coEvery { dao.insert(capture(itemSlot)) } returns 1L

        repository.addExpense("餐饮", 2000.0, "MONTHLY")

        assertThat(itemSlot.captured.sortOrder).isEqualTo(0)
    }

    @Test
    fun `updateExpense updates editable fields and timestamp`() = runTest {
        val existing = LivingExpenseItemEntity(
            id = 2,
            name = "餐饮",
            amount = 2000.0,
            period = "MONTHLY",
            sortOrder = 1,
            createdAt = 100,
            updatedAt = 100
        )
        val updatedSlot = slot<LivingExpenseItemEntity>()
        coEvery { dao.getById(2) } returns existing

        repository.updateExpense(2, "食品", 24000.0, "YEARLY")

        coVerify { dao.update(capture(updatedSlot)) }
        assertThat(updatedSlot.captured.name).isEqualTo("食品")
        assertThat(updatedSlot.captured.amount).isEqualTo(24000.0)
        assertThat(updatedSlot.captured.period).isEqualTo("YEARLY")
        assertThat(updatedSlot.captured.sortOrder).isEqualTo(1)
        assertThat(updatedSlot.captured.createdAt).isEqualTo(100)
        assertThat(updatedSlot.captured.updatedAt).isGreaterThan(100)
    }

    @Test
    fun `moveUp swaps with previous item`() = runTest {
        val itemsFlow = MutableStateFlow(
            listOf(
                LivingExpenseItemEntity(1, "房租", 3000.0, "MONTHLY", 0),
                LivingExpenseItemEntity(2, "餐饮", 2000.0, "MONTHLY", 1)
            )
        )
        every { dao.observeAll() } returns itemsFlow
        coEvery { dao.getAllOnce() } returns itemsFlow.value

        repository.moveUp(2)

        coVerify { dao.updateSortOrders(2, 0, any()) }
        coVerify { dao.updateSortOrders(1, 1, any()) }
    }

    @Test
    fun `moveDown swaps with next item`() = runTest {
        val items = listOf(
            LivingExpenseItemEntity(1, "房租", 3000.0, "MONTHLY", 0),
            LivingExpenseItemEntity(2, "餐饮", 2000.0, "MONTHLY", 1)
        )
        coEvery { dao.getAllOnce() } returns items

        repository.moveDown(1)

        coVerify { dao.updateSortOrders(1, 1, any()) }
        coVerify { dao.updateSortOrders(2, 0, any()) }
    }
}
```

- [ ] **Step 2: Run the repository test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.data.repository.LivingExpenseRepositoryTest"
```

Expected: FAIL because DAO, entity, and repository do not exist.

- [ ] **Step 3: Add entity and DAO**

Create `app/src/main/java/com/stock/dividend/data/local/entity/LivingExpenseItemEntity.kt`:

```kotlin
package com.stock.dividend.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

const val EXPENSE_PERIOD_MONTHLY = "MONTHLY"
const val EXPENSE_PERIOD_YEARLY = "YEARLY"

@Entity(tableName = "living_expense_items")
data class LivingExpenseItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val amount: Double,
    val period: String,
    val sortOrder: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

Create `app/src/main/java/com/stock/dividend/data/local/dao/LivingExpenseItemDao.kt`:

```kotlin
package com.stock.dividend.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LivingExpenseItemDao {
    @Query("SELECT * FROM living_expense_items ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<LivingExpenseItemEntity>>

    @Query("SELECT * FROM living_expense_items ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllOnce(): List<LivingExpenseItemEntity>

    @Query("SELECT * FROM living_expense_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LivingExpenseItemEntity?

    @Query("SELECT MAX(sortOrder) FROM living_expense_items")
    suspend fun getMaxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: LivingExpenseItemEntity): Long

    @Update
    suspend fun update(item: LivingExpenseItemEntity)

    @Delete
    suspend fun delete(item: LivingExpenseItemEntity)

    @Query("DELETE FROM living_expense_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE living_expense_items SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSortOrders(id: Long, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())
}
```

- [ ] **Step 4: Add repository**

Create `app/src/main/java/com/stock/dividend/data/repository/LivingExpenseRepository.kt`:

```kotlin
package com.stock.dividend.data.repository

import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LivingExpenseRepository @Inject constructor(
    private val dao: LivingExpenseItemDao
) {
    fun observeExpenses(): Flow<List<LivingExpenseItemEntity>> = dao.observeAll()

    suspend fun addExpense(name: String, amount: Double, period: String): Long {
        requireValidExpense(name, amount, period)
        val nextOrder = (dao.getMaxSortOrder() ?: -1) + 1
        return dao.insert(
            LivingExpenseItemEntity(
                name = name.trim(),
                amount = amount,
                period = period,
                sortOrder = nextOrder
            )
        )
    }

    suspend fun updateExpense(id: Long, name: String, amount: Double, period: String) {
        requireValidExpense(name, amount, period)
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                name = name.trim(),
                amount = amount,
                period = period,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteExpense(id: Long) {
        dao.deleteById(id)
    }

    suspend fun moveUp(id: Long) {
        val items = dao.getAllOnce()
        val index = items.indexOfFirst { it.id == id }
        if (index <= 0) return
        swapOrders(items[index], items[index - 1])
    }

    suspend fun moveDown(id: Long) {
        val items = dao.getAllOnce()
        val index = items.indexOfFirst { it.id == id }
        if (index == -1 || index >= items.lastIndex) return
        swapOrders(items[index], items[index + 1])
    }

    private suspend fun swapOrders(first: LivingExpenseItemEntity, second: LivingExpenseItemEntity) {
        val now = System.currentTimeMillis()
        dao.updateSortOrders(first.id, second.sortOrder, now)
        dao.updateSortOrders(second.id, first.sortOrder, now)
    }

    private fun requireValidExpense(name: String, amount: Double, period: String) {
        require(name.isNotBlank()) { "支出名称不能为空" }
        require(amount > 0.0) { "支出金额必须大于零" }
        require(amount <= 999_999_999_999.0) { "金额超出有效范围" }
        require(period == EXPENSE_PERIOD_MONTHLY || period == EXPENSE_PERIOD_YEARLY) { "支出周期无效" }
    }
}
```

- [ ] **Step 5: Wire Room database and Hilt**

Modify `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt`:

```kotlin
import com.stock.dividend.data.local.dao.LivingExpenseItemDao
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
```

Change the `@Database` annotation to:

```kotlin
@Database(
    entities = [
        StockEntity::class,
        DividendEntity::class,
        FireGoalEntity::class,
        DividendIncomeRecordEntity::class,
        TransactionEntity::class,
        AchievementEntity::class,
        LivingExpenseItemEntity::class
    ],
    version = 8,
    exportSchema = false
)
```

Add the DAO function:

```kotlin
abstract fun livingExpenseItemDao(): LivingExpenseItemDao
```

Add migration inside `companion object`:

```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `living_expense_items` (" +
                    "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "`name` TEXT NOT NULL, " +
                    "`amount` REAL NOT NULL, " +
                    "`period` TEXT NOT NULL, " +
                    "`sortOrder` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_living_expense_items_sortOrder` " +
                    "ON `living_expense_items`(`sortOrder`)"
        )
    }
}
```

Modify `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt`:

```kotlin
import com.stock.dividend.data.local.dao.LivingExpenseItemDao
```

Add `AppDatabase.MIGRATION_7_8` to `.addMigrations(...)`.

Add provider:

```kotlin
@Provides
fun provideLivingExpenseItemDao(db: AppDatabase): LivingExpenseItemDao = db.livingExpenseItemDao()
```

- [ ] **Step 6: Run repository test to verify it passes**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.data.repository.LivingExpenseRepositoryTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/stock/dividend/data/local/entity/LivingExpenseItemEntity.kt app/src/main/java/com/stock/dividend/data/local/dao/LivingExpenseItemDao.kt app/src/main/java/com/stock/dividend/data/repository/LivingExpenseRepository.kt app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt app/src/main/java/com/stock/dividend/di/DatabaseModule.kt app/src/test/java/com/stock/dividend/data/repository/LivingExpenseRepositoryTest.kt
git commit -m "Add living expense persistence"
```

---

### Task 3: ExpenseCoverageViewModel State And Actions

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/viewmodel/ExpenseCoverageViewModel.kt`
- Test: `app/src/test/java/com/stock/dividend/viewmodel/ExpenseCoverageViewModelTest.kt`

- [ ] **Step 1: Write the failing ViewModel test**

Create `app/src/test/java/com/stock/dividend/viewmodel/ExpenseCoverageViewModelTest.kt`:

```kotlin
package com.stock.dividend.viewmodel

import com.google.common.truth.Truth.assertThat
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseCoverageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dividendIncomeRepository: DividendIncomeRepository = mockk(relaxed = true)
    private val livingExpenseRepository: LivingExpenseRepository = mockk(relaxed = true)
    private val forecastFlow = MutableStateFlow(0.0)
    private val expensesFlow = MutableStateFlow<List<LivingExpenseItemEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { dividendIncomeRepository.observeForecastTotal() } returns forecastFlow
        every { livingExpenseRepository.observeExpenses() } returns expensesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState uses forecast income to cover living expenses`() = runTest {
        forecastFlow.value = 45_000.0
        expensesFlow.value = listOf(
            LivingExpenseItemEntity(1, "房租", 3000.0, EXPENSE_PERIOD_MONTHLY, 0),
            LivingExpenseItemEntity(2, "餐饮", 18_000.0, EXPENSE_PERIOD_YEARLY, 1)
        )

        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.forecastAnnualDividendIncome).isEqualTo(45_000.0)
        assertThat(state.totalAnnualExpense).isEqualTo(54_000.0)
        assertThat(state.coverageRatio).isWithin(0.0001).of(45_000.0 / 54_000.0)
        assertThat(state.coveredItemCount).isEqualTo(1)
        assertThat(state.currentCoveringItemName).isEqualTo("餐饮")
        assertThat(state.rows).hasSize(2)
        assertThat(state.rows[0].status).isEqualTo(CoverageStatus.COVERED)
        assertThat(state.rows[1].status).isEqualTo(CoverageStatus.PARTIAL)
    }

    @Test
    fun `saveExpense validates blank name`() = runTest {
        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)

        viewModel.onExpenseNameChanged(" ")
        viewModel.onExpenseAmountChanged("100")
        viewModel.saveExpense()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dialogError).isEqualTo("请输入支出名称")
    }

    @Test
    fun `saveExpense validates invalid amount`() = runTest {
        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)

        viewModel.onExpenseNameChanged("房租")
        viewModel.onExpenseAmountChanged("abc")
        viewModel.saveExpense()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dialogError).isEqualTo("请输入有效金额")
    }

    @Test
    fun `saveExpense adds new expense and closes dialog`() = runTest {
        coEvery { livingExpenseRepository.addExpense(any(), any(), any()) } returns 1L
        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)

        viewModel.showAddDialog()
        viewModel.onExpenseNameChanged("房租")
        viewModel.onExpenseAmountChanged("3000")
        viewModel.onExpensePeriodChanged(ExpensePeriod.MONTHLY)
        viewModel.saveExpense()
        advanceUntilIdle()

        coVerify { livingExpenseRepository.addExpense("房租", 3000.0, EXPENSE_PERIOD_MONTHLY) }
        assertThat(viewModel.uiState.value.showExpenseDialog).isFalse()
    }

    @Test
    fun `move and delete actions delegate to repository`() = runTest {
        val viewModel = ExpenseCoverageViewModel(dividendIncomeRepository, livingExpenseRepository)

        viewModel.moveExpenseUp(2)
        viewModel.moveExpenseDown(1)
        viewModel.deleteExpense(3)
        advanceUntilIdle()

        coVerify { livingExpenseRepository.moveUp(2) }
        coVerify { livingExpenseRepository.moveDown(1) }
        coVerify { livingExpenseRepository.deleteExpense(3) }
    }
}
```

- [ ] **Step 2: Run the ViewModel test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.viewmodel.ExpenseCoverageViewModelTest"
```

Expected: FAIL because `ExpenseCoverageViewModel` still expects `FireGoalRepository` and does not expose living expense state/actions.

- [ ] **Step 3: Replace ExpenseCoverageViewModel**

Replace `app/src/main/java/com/stock/dividend/viewmodel/ExpenseCoverageViewModel.kt` with:

```kotlin
package com.stock.dividend.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_MONTHLY
import com.stock.dividend.data.local.entity.EXPENSE_PERIOD_YEARLY
import com.stock.dividend.data.local.entity.LivingExpenseItemEntity
import com.stock.dividend.data.repository.DividendIncomeRepository
import com.stock.dividend.data.repository.LivingExpenseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExpenseCoverageUiState(
    val forecastAnnualDividendIncome: Double = 0.0,
    val totalAnnualExpense: Double = 0.0,
    val coverageRatio: Double = 0.0,
    val coveredItemCount: Int = 0,
    val currentCoveringItemName: String? = null,
    val remainingSurplus: Double = 0.0,
    val rows: List<ExpenseCoverageRow> = emptyList(),
    val showExpenseDialog: Boolean = false,
    val editingExpenseId: Long? = null,
    val expenseNameInput: String = "",
    val expenseAmountInput: String = "",
    val expensePeriodInput: ExpensePeriod = ExpensePeriod.MONTHLY,
    val dialogError: String? = null
) {
    val hasExpenses: Boolean = rows.isNotEmpty()
}

@HiltViewModel
class ExpenseCoverageViewModel @Inject constructor(
    private val dividendIncomeRepository: DividendIncomeRepository,
    private val livingExpenseRepository: LivingExpenseRepository
) : ViewModel() {
    private val dialogState = MutableStateFlow(ExpenseCoverageUiState())

    val uiState: StateFlow<ExpenseCoverageUiState> = combine(
        dividendIncomeRepository.observeForecastTotal(),
        livingExpenseRepository.observeExpenses(),
        dialogState
    ) { forecastIncome, expenses, dialog ->
        val calculation = ExpenseCoverageCalculator.calculate(
            forecastAnnualDividendIncome = forecastIncome,
            items = expenses.map { it.toCoverageInput() }
        )
        dialog.copy(
            forecastAnnualDividendIncome = calculation.forecastAnnualDividendIncome,
            totalAnnualExpense = calculation.totalAnnualExpense,
            coverageRatio = calculation.coverageRatio,
            coveredItemCount = calculation.coveredItemCount,
            currentCoveringItemName = calculation.currentCoveringItemName,
            remainingSurplus = calculation.remainingSurplus,
            rows = calculation.rows
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExpenseCoverageUiState())

    fun showAddDialog() {
        dialogState.update {
            it.copy(
                showExpenseDialog = true,
                editingExpenseId = null,
                expenseNameInput = "",
                expenseAmountInput = "",
                expensePeriodInput = ExpensePeriod.MONTHLY,
                dialogError = null
            )
        }
    }

    fun showEditDialog(row: ExpenseCoverageRow) {
        dialogState.update {
            it.copy(
                showExpenseDialog = true,
                editingExpenseId = row.id,
                expenseNameInput = row.name,
                expenseAmountInput = formatAmountForInput(row.amount),
                expensePeriodInput = row.period,
                dialogError = null
            )
        }
    }

    fun dismissDialog() {
        dialogState.update { it.copy(showExpenseDialog = false, dialogError = null) }
    }

    fun onExpenseNameChanged(input: String) {
        dialogState.update { it.copy(expenseNameInput = input, dialogError = null) }
    }

    fun onExpenseAmountChanged(input: String) {
        dialogState.update { it.copy(expenseAmountInput = input, dialogError = null) }
    }

    fun onExpensePeriodChanged(period: ExpensePeriod) {
        dialogState.update { it.copy(expensePeriodInput = period, dialogError = null) }
    }

    fun saveExpense() {
        val state = dialogState.value
        val name = state.expenseNameInput.trim()
        val amount = state.expenseAmountInput.trim().toDoubleOrNull()
        when {
            name.isBlank() -> dialogState.update { it.copy(dialogError = "请输入支出名称") }
            amount == null -> dialogState.update { it.copy(dialogError = "请输入有效金额") }
            amount <= 0.0 -> dialogState.update { it.copy(dialogError = "支出金额必须大于零") }
            amount > 999_999_999_999.0 -> dialogState.update { it.copy(dialogError = "金额超出有效范围") }
            else -> viewModelScope.launch {
                val period = state.expensePeriodInput.toStorageValue()
                val editingId = state.editingExpenseId
                if (editingId == null) {
                    livingExpenseRepository.addExpense(name, amount, period)
                } else {
                    livingExpenseRepository.updateExpense(editingId, name, amount, period)
                }
                dialogState.update { it.copy(showExpenseDialog = false, dialogError = null) }
            }
        }
    }

    fun deleteExpense(id: Long) {
        viewModelScope.launch {
            livingExpenseRepository.deleteExpense(id)
        }
    }

    fun moveExpenseUp(id: Long) {
        viewModelScope.launch {
            livingExpenseRepository.moveUp(id)
        }
    }

    fun moveExpenseDown(id: Long) {
        viewModelScope.launch {
            livingExpenseRepository.moveDown(id)
        }
    }

    private fun LivingExpenseItemEntity.toCoverageInput(): CoverageExpenseInput =
        CoverageExpenseInput(
            id = id,
            name = name,
            amount = amount,
            period = period.toExpensePeriod(),
            sortOrder = sortOrder
        )

    private fun String.toExpensePeriod(): ExpensePeriod =
        if (this == EXPENSE_PERIOD_YEARLY) ExpensePeriod.YEARLY else ExpensePeriod.MONTHLY

    private fun ExpensePeriod.toStorageValue(): String =
        when (this) {
            ExpensePeriod.MONTHLY -> EXPENSE_PERIOD_MONTHLY
            ExpensePeriod.YEARLY -> EXPENSE_PERIOD_YEARLY
        }

    private fun formatAmountForInput(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else "%.2f".format(amount)
}
```

- [ ] **Step 4: Run the ViewModel test to verify it passes**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.stock.dividend.viewmodel.ExpenseCoverageViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stock/dividend/viewmodel/ExpenseCoverageViewModel.kt app/src/test/java/com/stock/dividend/viewmodel/ExpenseCoverageViewModelTest.kt
git commit -m "Connect expense coverage state"
```

---

### Task 4: Compose Expense Coverage Page

**Files:**
- Modify: `app/src/main/java/com/stock/dividend/ui/screen/ExpenseCoverageScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Run existing tests before UI work**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS for all unit tests added so far.

- [ ] **Step 2: Add strings**

Add these strings to `app/src/main/res/values/strings.xml`, replacing the old `expense_coverage_*` labels where names conflict:

```xml
<string name="expense_coverage_title">生活支出覆盖</string>
<string name="expense_coverage_description">用预测年度股息收入按顺序覆盖你的生活支出。</string>
<string name="expense_coverage_status_title">生活支持进度</string>
<string name="expense_coverage_metric_forecast_income">预测年度股息</string>
<string name="expense_coverage_metric_total_expense">年度生活支出</string>
<string name="expense_coverage_metric_covered_items">已覆盖支出</string>
<string name="expense_coverage_current_item">正在覆盖：%1$s</string>
<string name="expense_coverage_all_covered">全部生活支出已覆盖</string>
<string name="expense_coverage_no_income">还没有可用于覆盖支出的预测股息</string>
<string name="expense_coverage_empty_title">还没有生活支出项</string>
<string name="expense_coverage_empty_message">添加房租、餐饮、交通等支出，查看股息收入可以先覆盖哪些生活支持。</string>
<string name="expense_coverage_action_add_expense">添加支出</string>
<string name="expense_coverage_action_edit">编辑</string>
<string name="expense_coverage_action_delete">删除</string>
<string name="expense_coverage_action_move_up">上移</string>
<string name="expense_coverage_action_move_down">下移</string>
<string name="expense_coverage_dialog_add_title">添加生活支出</string>
<string name="expense_coverage_dialog_edit_title">编辑生活支出</string>
<string name="expense_coverage_name_label">支出名称</string>
<string name="expense_coverage_amount_label">金额</string>
<string name="expense_coverage_period_monthly">每月</string>
<string name="expense_coverage_period_yearly">每年</string>
<string name="expense_coverage_annual_amount">年化 ¥%1$.2f</string>
<string name="expense_coverage_covered_amount">已覆盖 ¥%1$.2f</string>
<string name="expense_coverage_gap_amount">缺口 ¥%1$.2f</string>
<string name="expense_coverage_status_covered">已覆盖</string>
<string name="expense_coverage_status_partial">部分覆盖</string>
<string name="expense_coverage_status_uncovered">未覆盖</string>
```

- [ ] **Step 3: Replace ExpenseCoverageScreen UI**

Replace `app/src/main/java/com/stock/dividend/ui/screen/ExpenseCoverageScreen.kt` with a Compose implementation that includes these functions:

```kotlin
@Composable
fun ExpenseCoverageScreen(
    onBack: () -> Unit,
    onGoSetup: () -> Unit,
    viewModel: ExpenseCoverageViewModel = hiltViewModel()
)
```

Keep `onGoSetup` in the signature for route compatibility, but do not call it.

The file must define:

```kotlin
private fun formatMoney(value: Double): String = "¥%.2f".format(value)

private fun formatPeriodAmount(amount: Double, period: ExpensePeriod): String =
    when (period) {
        ExpensePeriod.MONTHLY -> "${formatMoney(amount)} / 月"
        ExpensePeriod.YEARLY -> "${formatMoney(amount)} / 年"
    }
```

The top-level content must use:

```kotlin
Scaffold(
    topBar = {
        CompactTopAppBar(
            title = stringResource(R.string.expense_coverage_title),
            onBack = onBack
        )
    },
    floatingActionButton = {
        ExtendedFloatingActionButton(
            onClick = onAddExpense,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.expense_coverage_action_add_expense)) }
        )
    }
)
```

The summary card must display:

```kotlin
Text("%.1f%%".format(state.coverageRatio * 100))
MetricRow(stringResource(R.string.expense_coverage_metric_forecast_income), formatMoney(state.forecastAnnualDividendIncome))
MetricRow(stringResource(R.string.expense_coverage_metric_total_expense), formatMoney(state.totalAnnualExpense))
MetricRow(stringResource(R.string.expense_coverage_metric_covered_items), "${state.coveredItemCount}/${state.rows.size}")
```

Each row must show:

```kotlin
Text(row.name)
Text(formatPeriodAmount(row.amount, row.period))
Text(stringResource(R.string.expense_coverage_annual_amount, row.annualAmount))
Text(stringResource(statusStringRes(row.status)))
Text(stringResource(R.string.expense_coverage_covered_amount, row.coveredAmount))
Text(stringResource(R.string.expense_coverage_gap_amount, row.gapAmount))
```

Each row must call:

```kotlin
viewModel.moveExpenseUp(row.id)
viewModel.moveExpenseDown(row.id)
viewModel.showEditDialog(row)
viewModel.deleteExpense(row.id)
```

Use icon buttons from Material icons:

```kotlin
Icons.Default.KeyboardArrowUp
Icons.Default.KeyboardArrowDown
Icons.Default.Edit
Icons.Default.Delete
```

Use `AlertDialog` for add/edit with:

```kotlin
OutlinedTextField(
    value = state.expenseNameInput,
    onValueChange = onNameChanged,
    label = { Text(stringResource(R.string.expense_coverage_name_label)) },
    singleLine = true
)

OutlinedTextField(
    value = state.expenseAmountInput,
    onValueChange = onAmountChanged,
    label = { Text(stringResource(R.string.expense_coverage_amount_label)) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
)

SingleChoiceSegmentedButtonRow {
    SegmentedButton(
        selected = state.expensePeriodInput == ExpensePeriod.MONTHLY,
        onClick = { onPeriodChanged(ExpensePeriod.MONTHLY) },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
    ) { Text(stringResource(R.string.expense_coverage_period_monthly)) }
    SegmentedButton(
        selected = state.expensePeriodInput == ExpensePeriod.YEARLY,
        onClick = { onPeriodChanged(ExpensePeriod.YEARLY) },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
    ) { Text(stringResource(R.string.expense_coverage_period_yearly)) }
}
```

- [ ] **Step 4: Compile the UI**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Run full unit tests**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stock/dividend/ui/screen/ExpenseCoverageScreen.kt app/src/main/res/values/strings.xml
git commit -m "Build living expense coverage UI"
```

---

## Final Verification

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Compile debug Kotlin**

```bash
./gradlew compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Inspect git status**

```bash
git status --short
```

Expected: only unrelated pre-existing files remain modified; files from this feature are committed.

---

## Self-Review

Spec coverage:

- Persistent living expense items: Task 2.
- Monthly/yearly periods and annualization: Tasks 1, 2, 3, 4.
- Default add order: Task 2.
- Manual order changes: Tasks 2, 3, 4.
- Forecast annual dividend income as source: Tasks 1 and 3.
- Covered/partial/uncovered rows: Tasks 1, 3, 4.
- Existing route reuse: Task 4.
- Existing FIRE goal compatibility: Tasks 3 and 4 remove coverage dependency on `fire_goal` without deleting the old model.

Placeholder scan:

- No implementation placeholders are intentionally left in the task steps.
- Commands include expected outcomes.

Type consistency:

- `ExpensePeriod`, `CoverageStatus`, `CoverageExpenseInput`, `ExpenseCoverageRow`, and `ExpenseCoverageUiState` are introduced before use.
- Repository method names used by the ViewModel match Task 2.
