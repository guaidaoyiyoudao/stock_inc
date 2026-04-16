# Tasks: FIRE Retirement Goal Progress

**Input**: Design documents from `/specs/004-fire-retirement-goal/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Not explicitly requested — test tasks omitted.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: No project initialization needed — existing Android project with all dependencies already configured.

No setup tasks required.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core data layer that ALL user stories depend on. MUST complete before any user story work begins.

- [x] T001 Create FireGoalEntity Room entity with fields id (Long, PK, AUTOINCREMENT), targetAmount (Double, NOT NULL), createdAt (Long, NOT NULL), updatedAt (Long, NOT NULL) in `app/src/main/java/com/stock/dividend/data/local/entity/FireGoalEntity.kt`
- [x] T002 [P] Create FireGoalDao with methods observe() → Flow<FireGoalEntity?>, getOnce() → suspend FireGoalEntity?, insert(goal) → suspend Unit, update(goal) → suspend Unit, delete() → suspend Unit in `app/src/main/java/com/stock/dividend/data/local/dao/FireGoalDao.kt`
- [x] T003 Add FireGoalEntity to AppDatabase entities array, bump version to 3, add MIGRATION_2_3 (CREATE TABLE fire_goal), expose fireGoalDao() in `app/src/main/java/com/stock/dividend/data/local/AppDatabase.kt` (depends on T001, T002)
- [x] T004 [P] Add Hilt @Provides for FireGoalDao in DatabaseModule in `app/src/main/java/com/stock/dividend/di/DatabaseModule.kt` (depends on T002)
- [x] T005 Create FireGoalRepository @Singleton with observeGoal(), getGoalOnce(), saveGoal(amount), updateGoal(amount), deleteGoal() in `app/src/main/java/com/stock/dividend/data/repository/FireGoalRepository.kt` (depends on T002)

**Checkpoint**: Data layer complete — entity, DAO, migration, DI, and repository ready.

---

## Phase 3: User Story 1 — Set FIRE Target Amount (Priority: P1) MVP

**Goal**: User can tap FIRE card on home page, navigate to setup screen, enter a target amount, and save it.

**Independent Test**: Tap FIRE prompt → enter amount → confirm → verify amount saved in DB → return to home page.

### Implementation for User Story 1

- [x] T006 [US1] Create FireGoalViewModel @HiltViewModel with uiState (amountInput, error, isSaving, existingGoal), loadExistingGoal(), setAmount(input), saveGoal(), inject FireGoalRepository in `app/src/main/java/com/stock/dividend/viewmodel/FireGoalViewModel.kt` (depends on T005)
- [x] T007 [US1] Create FireGoalSetupScreen composable with TopAppBar ("FIRE 目标"), OutlinedTextField for amount input, "确认" save button, validation error display, load existing goal on init in `app/src/main/java/com/stock/dividend/ui/screen/FireGoalSetupScreen.kt` (depends on T006)
- [x] T008 [US1] Add FIRE_GOAL_SETUP = "fireGoalSetup" route to Routes object, add NavHost composable for FireGoalSetupScreen with back navigation in `app/src/main/java/com/stock/dividend/ui/navigation/AppNavigation.kt` (depends on T007)

**Checkpoint**: User Story 1 complete — user can navigate to setup screen and save a FIRE target amount.

---

## Phase 4: User Story 2 — View FIRE Progress on Home Page (Priority: P1)

**Goal**: Home page displays a FIRE progress card showing expected annual dividend income as % of target amount with a linear progress bar.

**Independent Test**: Set a target via US1 → return to home page → verify progress bar shows correct % and amounts.

### Implementation for User Story 2

- [x] T009 [US2] Extend HomeUiState with fireGoal (FireGoalEntity?) and fireProgress (Float? = forecastTotal / goal.targetAmount * 100, capped at 100). Add FireGoalDao injection to HomeViewModel, observe fireGoal reactively, combine with existing forecastTotal to compute progress in `app/src/main/java/com/stock/dividend/viewmodel/HomeViewModel.kt` (depends on T002, T005)
- [x] T010 [P] [US2] Create FireProgressCard composable: when goal not set, show "设置 FIRE 目标" prompt card; when set, show LinearProgressIndicator + percentage text + "¥{forecast} / ¥{target}" amounts; when achieved (>=100%), show accent styling and "已达标" badge. Accept onClick lambda in `app/src/main/java/com/stock/dividend/ui/component/FireProgressCard.kt`
- [x] T011 [US2] Integrate FireProgressCard into HomeScreen LazyColumn at top (above DividendSummaryCard). Add onFireCardClick navigation callback to fireGoalSetup route. Pass fireGoal and forecastTotal from HomeUiState in `app/src/main/java/com/stock/dividend/ui/screen/HomeScreen.kt` (depends on T009, T010)

**Checkpoint**: User Stories 1 AND 2 complete — user sees FIRE progress on home page with correct percentage.

---

## Phase 5: User Story 3 — Modify & Delete FIRE Target (Priority: P2)

**Goal**: User can tap the FIRE card to modify existing target amount or delete it entirely with confirmation.

**Independent Test**: Modify target amount → verify home page updates; delete target → verify home page shows setup prompt.

### Implementation for User Story 3

- [x] T012 [US3] Add updateGoal(amount) and deleteGoal() methods to FireGoalViewModel. Add deleteConfirm state and showDeleteDialog toggle in `app/src/main/java/com/stock/dividend/viewmodel/FireGoalViewModel.kt` (depends on T006)
- [x] T013 [US3] Extend FireGoalSetupScreen: pre-fill amount input when existing goal loaded, show "删除目标" red text button at bottom when goal exists, add AlertDialog for delete confirmation ("确认删除 FIRE 目标？"), call updateGoal on save when goal exists in `app/src/main/java/com/stock/dividend/ui/screen/FireGoalSetupScreen.kt` (depends on T007, T012)

**Checkpoint**: All user stories complete — full FIRE goal lifecycle (create, read, update, delete) works end-to-end.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Edge cases, formatting, and final validation.

- [x] T014 Format large amounts with unit conversion (e.g., "¥1.25 亿" for amounts >= 100,000,000) in FireProgressCard and FireGoalSetupScreen display in `app/src/main/java/com/stock/dividend/ui/component/FireProgressCard.kt` and `app/src/main/java/com/stock/dividend/ui/screen/FireGoalSetupScreen.kt`
- [x] T015 Verify dark mode rendering for FireProgressCard and FireGoalSetupScreen — ensure progress bar and amounts use MaterialTheme.colorScheme colors
- [ ] T016 Run quickstart.md validation checklist — verify all 10 items on a real device or emulator

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: No dependencies — start immediately
- **US1 (Phase 3)**: Depends on Phase 2 completion
- **US2 (Phase 4)**: Depends on Phase 2 (for FireGoalDao in HomeViewModel), can proceed in parallel with Phase 3
- **US3 (Phase 5)**: Depends on Phase 3 (extends FireGoalViewModel and FireGoalSetupScreen)
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **US1 (P1)**: Depends on Foundational only — no story dependencies
- **US2 (P1)**: Depends on Foundational only — can start in parallel with US1 (but needs US1 to fully test end-to-end)
- **US3 (P2)**: Depends on US1 (extends same ViewModel and Screen) — MUST complete US1 first

### Within Each User Story

- Models/entities before DAOs
- DAOs before repositories
- Repositories before ViewModels
- ViewModels before Screens
- Screens before Navigation integration

### Parallel Opportunities

- T001 and T002 can run in parallel (different files)
- T004 and T005 can run in parallel (different files, both depend on T002)
- T009 and T010 can run in parallel (T009 modifies ViewModel, T010 creates new component)

---

## Parallel Example: Foundational Phase

```bash
# Phase 2 parallel batch 1:
Task: "T001 Create FireGoalEntity in app/.../entity/FireGoalEntity.kt"
Task: "T002 Create FireGoalDao in app/.../dao/FireGoalDao.kt"

# Phase 2 sequential (after T001 + T002):
Task: "T003 Update AppDatabase with migration in app/.../AppDatabase.kt"

# Phase 2 parallel batch 2 (after T002):
Task: "T004 Add FireGoalDao to DatabaseModule in app/.../di/DatabaseModule.kt"
Task: "T005 Create FireGoalRepository in app/.../repository/FireGoalRepository.kt"
```

## Parallel Example: Phase 3+4

```bash
# T009 (ViewModel) and T010 (Card component) can run in parallel:
Task: "T009 Extend HomeViewModel with FIRE state in app/.../viewmodel/HomeViewModel.kt"
Task: "T010 Create FireProgressCard in app/.../ui/component/FireProgressCard.kt"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2)

1. Complete Phase 2: Foundational (data layer)
2. Complete Phase 3: US1 (set target + navigation)
3. Complete Phase 4: US2 (display progress on home page)
4. **STOP and VALIDATE**: Test setting a target and seeing progress
5. Deploy/demo if ready

### Full Feature

1. Complete MVP above
2. Complete Phase 5: US3 (modify + delete)
3. Complete Phase 6: Polish
4. Full validation

---

## Notes

- [P] tasks = different files, no dependencies on incomplete work
- [Story] label maps task to specific user story for traceability
- Progress calculation reuses existing `HomeViewModel.forecastTotal` — no new forecast logic
- Database migration v2→v3 is additive (CREATE TABLE) — safe, non-destructive
- Commit after each task or logical group
- Stop at any checkpoint to validate independently
