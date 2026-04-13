# ValtPrinter — World-Class Coding Rules & Architecture Standard

> These rules are non-negotiable. Every line of production code in this project
> must comply. No exceptions without an explicit architectural decision record (ADR).

---

## 1. Clean Architecture — Layer Contracts

### 1.1 Layer Definitions & Hard Boundaries

| Layer | Package | Allowed Dependencies |
|---|---|---|
| **Domain** | `domain/` | Pure Kotlin only. Zero Android SDK, Room, or third-party imports. |
| **Data** | `data/` | Domain interfaces, Room, Retrofit, Sunmi SDK, DataStore. |
| **UI/Presentation** | `ui/` | Domain models, ViewModels, Jetpack Compose. Never Room entities. |

**Violation examples that are strictly forbidden:**
- A `@Entity` class appearing in any Composable parameter or ViewModel state.
- A `ViewModel` importing anything from `data.*` directly.
- A `UseCase` importing `android.*` or `androidx.*`.

### 1.2 Unidirectional Data Flow (UDF)

```
User Action → ViewModel (intent) → UseCase → Repository → Data Source
                    ↑                                           |
              UI State (StateFlow) ←←←←←←←←←←←←←←←←←←←←←←←←
```

- **StateFlow mandatory** for all UI state. Always expose via `asStateFlow()`.
- **SharedFlow for one-shot events** (snackbars, navigation). Use
  `extraBufferCapacity = 1` + `onBufferOverflow = DROP_OLDEST`.
- **collectAsStateWithLifecycle()** is the only permitted collection method in
  Compose. `collectAsState()` without lifecycle is forbidden — it leaks
  collectors in the background.
- **No side effects in Composables** outside of `LaunchedEffect`, `SideEffect`,
  or `DisposableEffect`. Never start coroutines directly in a composable body.

### 1.3 Single Source of Truth

Every piece of state has exactly ONE owner. If Room is the source of truth for
paired devices, the ViewModel must not maintain a parallel in-memory copy that
can drift. Derive everything from the authoritative stream.

---

## 2. SOLID Principles — Enforced, Not Suggested

### S — Single Responsibility
- One `UseCase` = one business operation. `ConnectToPrinterUseCase` connects.
  It does not scan, pair, or persist. If the name needs "And", split it.
- One `Repository interface` = one capability domain. See Rule 2-O below.
- No Kotlin file may exceed **300 lines**. Extract composables, mappers, or
  helpers into separate files when approaching the limit.
- No `@Composable` function may exceed **60 lines** of body. Extract
  sub-composables aggressively.
- No `ViewModel` may exceed **200 lines**. Surplus logic belongs in UseCases.

### O — Open/Closed
- All cross-layer dependencies go through **interfaces**, never concrete classes.
- Adding a new printer brand = new implementation class. Existing classes are
  not touched.

### L — Liskov Substitution
- Any implementation of a repository interface must be substitutable without
  changing the behaviour the caller expects. Do not return `null` where the
  interface contract promises a value; use `Result<T>` or sealed types instead.

### I — Interface Segregation *(currently missing — now enforced)*
- **No repository interface may exceed 6 methods.**
- `PrinterRepository` must be split by capability:

```kotlin
interface PrinterScanRepository      // startScan, stopScan, discoveredDevices
interface PrinterConnectionRepository // connect, disconnect, connectPaired, printerState
interface PrinterPrintRepository     // printChunk, finalCut, initPrintJob, printReceipt
interface PrinterHardwareRepository  // isUsbPresent, isBtBonded, autoConnectUsb
```

- ViewModels and UseCases inject only the interface they actually need.

### D — Dependency Inversion
- Inject via `@Inject constructor` everywhere. Manual instantiation of
  cross-layer objects (`MyRepo()`) is forbidden.
- Interfaces bound in Hilt modules with `@Binds`. Third-party objects with
  `@Provides`.
- `@Singleton` scope for repositories and data sources.
  `@ViewModelScoped` for anything that must die with the ViewModel.

---

## 3. Concurrency & Coroutine Contract

### 3.1 Dispatcher Rules

| Dispatcher | Permitted use |
|---|---|
| `Dispatchers.Main` | UI state emission, Compose orchestration only. |
| `Dispatchers.IO` | Room, DataStore, AIDL/IPC, network sockets. |
| `Dispatchers.Default` | Bitmap rendering, image dithering, payload parsing. |
| `Dispatchers.Unconfined` | **Forbidden** in production code. |

### 3.2 Scope Contract

- **`viewModelScope`**: UI-triggered work that must cancel when the screen
  dies. All ViewModel-launched coroutines live here.
- **`repositoryScope` (`SupervisorJob + Dispatchers.Main`)**: Resilience loops,
  heartbeat monitors, reconnection jobs. Must survive ViewModel death.
  Must be `SupervisorJob` so one failed child does not cancel siblings.
- **`GlobalScope`**: Absolutely forbidden.

### 3.3 Flow Operators — Mandatory Usage

- `distinctUntilChanged()`: Always apply on state flows before expensive
  downstream operations. Prevents redundant work on identical emissions.
- `debounce()`: Mandatory on search-input or scan-trigger flows.
- `conflate()`: Use on high-frequency telemetry flows (heartbeat, RSSI) to
  drop intermediate values the UI can never render anyway.
- `stateIn(SharingStarted.WhileSubscribed(5_000))`: The only permitted
  strategy for ViewModel-exposed StateFlows derived from cold flows.

### 3.4 Structured Concurrency Rules

- Every launched coroutine must have a defined cancellation path. If a job
  needs to run indefinitely, it must check `isActive` in its loop.
- Never swallow `CancellationException`. Always rethrow it.
- `withContext` is preferred over `launch` + `join` for sequential async work.
- Print job coroutines must use a `Mutex` to serialize access to the hardware
  buffer. Concurrent writes to a thermal printer corrupt the output.

### 3.5 Immutability Contract

- All state objects and domain entities MUST be `data class` with `val`
  properties only. Use `.copy()` for transitions.
- `MutableStateFlow` and `MutableSharedFlow` are private. Never expose mutable
  flows to the outside world.

---

## 4. Compose UI Standards

### 4.1 Stability Contract *(critical for performance)*

Every data class passed as a parameter to a `@Composable` function **must**
be annotated `@Stable` or `@Immutable`. Without this, Compose cannot skip
recomposition and every parent recompose cascades into children.

```kotlin
@Immutable
data class PairedDeviceUiModel(
    val id: String,
    val name: String,
    val connectionType: ConnectionType,
    val lastSeenLabel: String,
    val isBonded: Boolean,
    val isNearby: Boolean,
    val isLastUsed: Boolean
)
```

- Use `@Immutable` for data classes with only `val` primitive/immutable fields.
- Use `@Stable` for classes with observable or mutable fields that guarantee
  change notification.

### 4.2 Recomposition Hygiene

- **`derivedStateOf{}`**: Mandatory for any state computed from other states.
  Never recompute inside a composable body on every frame.

```kotlin
// WRONG
val filteredDevices = devices.filter { it.id !in pairedIds }

// CORRECT
val filteredDevices by remember(devices, pairedIds) {
    derivedStateOf { devices.filter { it.id !in pairedIds } }
}
```

- **`remember{}`**: All expensive objects (SnackbarHostState, animations,
  coroutine scopes) must be wrapped in `remember{}`.
- **Lambda stability**: Never pass an inline lambda as a Composable parameter
  in a hot recomposition path. Hoist it or wrap in `rememberUpdatedState`.
- **`key()`**: Mandatory on every `LazyColumn`/`LazyRow` item. Omitting it
  causes full list recomposition on data changes.

### 4.3 State Hoisting

- Composables must be stateless wherever possible. State lives in the ViewModel
  or in the nearest common ancestor that needs it.
- No `remember { mutableStateOf() }` inside a leaf composable that is used in
  a list — state will be lost on item reuse.

### 4.4 No Entity in UI — Zero Tolerance

- Room `@Entity` classes are **forbidden** above the data layer.
- Every screen receives a dedicated UI model class from the `ui/model` package.
- The mapping from entity to UI model happens in the ViewModel or a dedicated
  mapper, never in a Composable.

```
data/entity/PairedDeviceEntity   ← Room only
ui/model/PairedDeviceUiModel     ← Compose only
data/mapper/PairedDeviceMapper   ← The only crossing point
```

---

## 5. Domain Primitive Rule *(previously missing)*

Raw primitives (`String`, `Int`) passed across layer boundaries are a bug
magnet. Use `@JvmInline value class` for all domain identifiers.

```kotlin
@JvmInline value class PrintJobId(val value: String)
@JvmInline value class PrinterId(val value: String)
@JvmInline value class ReceiptWidthPx(val value: Int)
```

The compiler then prevents passing a `PrinterId` where a `PrintJobId` is
expected. Zero runtime overhead.

---

## 6. Receipt Rendering Engine

- **Fixed width**: All receipt templates MUST render into a `576px` wide
  bitmap (80mm @ 203 DPI). No exceptions.
- **Headless-first**: Every receipt composable must render without a live
  `Activity` using `BitmapRenderer`. If it requires user interaction to render,
  the design is wrong.
- **Bitmap lifecycle**: Recycle or null bitmap references immediately after
  flushing to the Sunmi hardware buffer. Never hold a receipt bitmap in memory
  past the print commit.
- **Chunked delivery**: Bitmaps larger than the printer's hardware buffer
  (typically 800px tall) MUST be split into chunks before transmission.
  Never send a full-page bitmap as a single write.

---

## 7. Error Handling & Result Types

- **`Result<T>` or sealed `PrintResult`**: All repository and data-source
  functions that can fail must return a typed result — never throw raw
  exceptions across layer boundaries.
- One result type per domain area. Do not mix `kotlin.Result<T>` and custom
  sealed classes — pick one and apply it project-wide.
- **Never catch `CancellationException`** and swallow it.
- Errors must carry a **machine-readable code** (enum or sealed subtype), not
  just a human message string, so callers can react programmatically.

```kotlin
sealed class PrintResult {
    object Success : PrintResult()
    data class Failure(val reason: String, val code: ErrorCode) : PrintResult()
}

enum class ErrorCode {
    NOT_CONNECTED, BUFFER_OVERFLOW, PAPER_OUT, SOCKET_TIMEOUT, UNKNOWN
}
```

- **Persistence first**: An AIDL/IPC request is only success-acknowledged after
  it is committed to Room. Never acknowledge before persisting.
- **Idempotency**: Every print job carries a unique external ID. Before
  executing, check Room for a previous job with the same ID to prevent
  duplicate prints on retry.

---

## 8. Logging & Observability

- **Structured log tags**: Use a constant tag per class, prefixed with
  `VALT_`. e.g., `VALT_SCAN`, `VALT_CONNECT`, `VALT_PRINT`.
- **No PII in logs**: MAC addresses, IP addresses, and job contents must be
  redacted or hashed in `Log.*` calls. Full data goes to Room only.
- **Failure logging in Room**: Every print failure MUST produce a `PrintJobEntity`
  record with a specific `ErrorCode` (see §7), timestamp, and device ID for
  auditing.
- **Log levels**: `Log.d` = debug tracing (stripped in release). `Log.i` =
  state transitions. `Log.w` = recoverable anomalies. `Log.e` = unrecoverable
  failures. Never use `Log.e` for expected error paths.

---

## 9. Mission-Critical Reliability

### 9.1 Zero-Loss Print Guarantee
- Incoming jobs are persisted to Room **before** acknowledgement.
- The queue dispatcher reads from Room, not from memory.
- A crash between persistence and delivery is safe: on restart, the
  dispatcher re-processes any job in `PENDING` or `IN_PROGRESS` state.

### 9.2 Resilience Loop Safety
- All async dispatch loops (`while(isActive)`) must wrap the loop **body**
  in `try-catch(Exception)`. A single malformed payload must not kill the loop.
- The catch block logs the failure to Room and advances to the next job.
- Recovery loops use `SupervisorJob`. One failed reconnect attempt must not
  cancel the entire recovery session.

### 9.3 Memory Safety
- SDK callbacks that hold a `Context` reference must use `WeakReference` or
  be explicitly released (`release(context)`) before the owning component dies.
- Foreground service keeps the process alive. When the service is destroyed,
  all active coroutine scopes tied to it must be cancelled.

### 9.4 Storage Sentinel
- Before writing to Room, check available disk space.
- If disk space is below 10MB, log a warning and surface a system notification.
  Do not silently fail writes.

### 9.5 Foreground Service
- The print server MUST maintain a persistent foreground notification.
- The notification must reflect live state: "Ready", "Printing job #X",
  "Printer disconnected — reconnecting".

---

## 10. Security Rules *(previously missing)*

- **No credentials, tokens, MAC addresses, or IPs in Logcat.**
- Any sensitive preference (auth token, API key) MUST be stored in
  `EncryptedSharedPreferences` or encrypted DataStore. Never plain
  `SharedPreferences`.
- **ProGuard/R8**: The app must have an active `proguard-rules.pro` that
  preserves Room entities, Hilt components, and Sunmi SDK classes while
  aggressively obfuscating business logic.
- **No hardcoded secrets** in source code. Use `local.properties` + BuildConfig
  fields, excluded from version control.

---

## 11. Testing — Enforced, Not Aspirational

### 11.1 What Must Be Tested
- Every `UseCase` `invoke()` function: 100% branch coverage. No exceptions.
- Every `Mapper` function: pure input/output, trivially testable.
- Every `ViewModel`: test state transitions using `Turbine` + `TestScope`.
- Room DAO operations: `androidTest` with an in-memory database.

### 11.2 Test Tooling
- **MockK** for mocking interfaces and coroutine-aware verification.
- **Turbine** for asserting Flow emissions in sequence.
- **`StandardTestDispatcher` + `TestScope`**: Mandatory for coroutine tests.
  Never use `runBlocking` in a test that launches coroutines.
- **`ComposeTestRule`**: For any Composable that has non-trivial interaction
  logic (not just rendering).

### 11.3 Snapshot Testing for Receipts
- Receipt templates MUST have screenshot snapshot tests. A pixel regression
  in a receipt is a real production bug — a customer gets an unreadable receipt.
- Use `Paparazzi` for headless Compose snapshot tests (no emulator needed).

### 11.4 Test Naming Convention
```kotlin
// Pattern: subjectUnderTest_condition_expectedOutcome
@Test fun connectToPrinter_whenDeviceNotFound_emitsErrorState()
@Test fun mapPairedEntity_withBluetoothType_returnsBtConnectionType()
```

---

## 12. Kotlin Code Quality Standards

### 12.1 Naming
- No abbreviations. `processedReceiptCount` not `prc`. `connectionType` not `ct`.
- Boolean properties and parameters use `is` / `has` / `can` prefix:
  `isConnected`, `hasPendingJobs`, `canRetry`.
- Functions that return `Unit` (commands) use imperative verbs: `connectDevice()`.
- Functions that return a value use noun or question form: `getActiveJob()`,
  `isPrinterPresent()`.

### 12.2 Explicit API Surface
- All public functions must have explicit return types. No type inference on
  public API.
- All public interfaces, classes, and `UseCase.invoke()` methods must have
  KDoc explaining the **why**, not the what. The what is readable from the code.

### 12.3 Sealed Classes & Enums
- Use `sealed interface` (not `sealed class`) for result/state types — lower
  memory overhead, no default constructor.
- Use `enum class` for finite, fixed sets of constants (connection types,
  error codes). Never represent them as raw strings across layer boundaries.

### 12.4 Kotlin Idioms
- Prefer `when` exhaustive expressions over `if-else` chains for sealed types.
- Use `apply`, `also`, `let`, `run` only when it genuinely improves readability.
  Do not chain scope functions more than 2 levels deep.
- `object` for stateless utility classes. `companion object` for factory methods.

---

## 13. Room & Database Rules

- **Migration required**: Every schema change requires a `Migration` object in
  `PersistenceModule`. `fallbackToDestructiveMigration()` is forbidden in
  production builds.
- **DAO method naming**: `upsert`, `getById`, `getAllFlow`, `deleteById`.
  Consistent naming makes DAOs predictable.
- **Flow from DAO**: Reactive queries MUST return `Flow<T>`. One-shot reads use
  `suspend fun`.
- **@Transaction**: Multi-table writes must be wrapped in `@Transaction` to
  guarantee atomicity.
- **Indexes**: Every column used in a `WHERE` clause must have a database index
  declared in the `@Entity` annotation.

---

## 14. Dependency & Build Rules

- **Version catalog**: All dependency versions live in `libs.versions.toml`.
  No hardcoded version strings in `build.gradle.kts`.
- **Baseline Profile**: The app must include a `baseline-prof.txt` generated
  from the critical user journey (launch → scan → connect → print). This
  guarantees AOT compilation of the hot path.
- **R8 full mode**: Enabled in release builds for maximum dead-code elimination.
- **No transitive dependency abuse**: Only import what a module directly uses.
  Do not add `implementation` dependencies that are only needed by a submodule.

---

## 15. AI Assistant Protocol *(revised for Claude Code)*

### 15.1 Default Behaviour
- The AI agent MUST read and comply with this `CODING_RULES.md` at the start
  of every session before writing or modifying any code.
- Rules are applied silently. The agent does not need to announce compliance —
  it simply writes compliant code.

### 15.2 When to Ask Clarifying Questions
Ask **only** when:
1. The request requires an architectural decision that has project-wide impact
   (e.g., "should we replace the Sunmi SDK with a custom BLE stack?").
2. Two valid architectural approaches exist and the choice has irreversible
   consequences.

Do **not** ask about:
- Implementation details the rules already answer.
- Naming, file placement, or pattern choice — the rules define these.
- Whether to write tests — always yes.

### 15.3 Rule Violation Protocol
If implementing a user request would require violating a rule in this document,
the agent MUST:
1. Implement the request in a compliant way.
2. Add a single comment at the end of the response explaining the compliance
   decision and any trade-off.

### 15.4 Code Review Mindset
Before finalising any implementation the agent must self-check:
- [ ] No Room `@Entity` leaked to the UI layer.
- [ ] No repository interface with more than 6 methods.
- [ ] No file exceeding 300 lines.
- [ ] No `@Composable` exceeding 60 lines of body.
- [ ] All state flows are private mutable / public immutable.
- [ ] All data classes passed to Composables are `@Stable` or `@Immutable`.
- [ ] All coroutines have a defined cancellation path.
- [ ] New public functions have KDoc.
