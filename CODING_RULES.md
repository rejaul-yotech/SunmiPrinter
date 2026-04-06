# ValtPrinter "Gold Standard" - Elite Coding Rules & Guidelines

## 1. Architectural Integrity (Clean Architecture)
The application MUST strictly adhere to **Clean Architecture** patterns to ensure testability, scalability, and decoupling from external frameworks.

### 1.1 Layer Separation
*   **Domain Layer**: MUST be pure Kotlin. Zero dependencies on Android SDK, Room, or Third-party UI libraries. Holds all business logic (UseCases), entities, and repository interfaces.
*   **Data Layer**: Responsible for fetching and persisting data. Implements repositories. Contains DTOs (Data Transfer Objects), Mappers, and Sources (Room, Retrofit, Sunmi SDK).
*   **UI/Presentation Layer**: Use **Jetpack Compose** only. ViewModels handle state and interact with UseCases. No direct repository access from the UI.

### 1.2 Data Flow Rule
*   Data MUST flow unidirectionally: UI -> ViewModel -> UseCase -> Repository.
*   **StateFlow Mandatory**: All UI state must be exposed as `StateFlow`. Use `asStateFlow()` to prevent external mutation.
*   **SharedFlow for Events**: One-time events (Toasts, Navigation) MUST use `SharedFlow` with `ExtraBufferCapacity = 1` and `onBufferOverflow = DROP_OLDEST` to prevent event re-triggering on rotation.
*   **Lifecycle-Aware Collection**: UI must ONLY collect flows using `repeatOnLifecycle` or `flowWithLifecycle` for background safety.

---

## 2. SOLID Principles & Elite Best Practices
Every class and function must strive for excellence through these rules:

*   **S (Single Responsibility)**: A UseCase must do exactly ONE thing (e.g., `GetActivePrintJob`). A Repository handles exactly ONE data source type.
*   **O (Open/Closed)**: Use interfaces for all dependencies. New functionality (e.g., adding a new printer brand) should be done via new implementation classes, not by modifying existing ones.
*   **D (Dependency Inversion)**: Always inject dependencies via `@Inject constructor`. 
*   **Mandatory Hilt**: ALL singletons, repositories, and data sources MUST be managed by Hilt. Manual instantiation (`new class()`) is strictly prohibited for cross-layer dependencies.
*   **Module Definition**: Interfaces must be bound in Hilt Modules using `@Binds`. Third-party or complex objects must be provided using `@Provides`.

---

## 3. Concurrency & Performance Engine (Coroutines & Flow)
High-concurrency printing requires extreme discipline:

### 3.1 Dispatcher Discipline
*   **Dispatchers.Main**: Used ONLY for UI updates and Compose composition.
*   **Dispatchers.IO**: Mandatory for Room DB operations, DataStore, and AIDL calls.
*   **Dispatchers.Default**: Mandatory for heavy computations (Bitmap rendering, payload parsing, image dithering).

### 3.2 Immutability
*   All state objects and Domain entities MUST be `data class` with `val` properties. 
*   Use `.copy()` for state transitions. This prevents side-effect bugs in multi-threaded environments.

---

## 4. UI & Rendering Standards (Headless Receipt Engine)
Since we are printing to hardware with specific physical widths:

*   **Fixed Dimension Rule**: All receipt templates MUST be wrapped in a container forced to exactly **576 pixels** width (80mm standard).
*   **Resource Management**: Bitmaps are expensive. Always recycle or nullify bitmap references immediately after flushing to the Sunmi hardware buffer.
*   **Headless-First**: Any UI developed for a receipt must be capable of rendering in a headless state (via `BitmapRenderer`) without user interaction.

---

## 5. Mission-Critical Reliability (The Zero-Loss Rule)
Our app is a system-level servant. It must be unshakeable:

*   **Persistence First**: An incoming AIDL request is only success-acknowledged after it is successfully committed to the Room DB.
*   **Error Handling**: Use `Result<T>` or `Either` patterns for repository calls. Never throw raw exceptions that can crash the servant process.
*   **Logging**: Every print failure MUST be logged in the Room DB with a specific reason (OOM, PaperOut, SocketTimeout) for future auditing.

---

## 6. Testing Strategy: "Zero-Bug" Commitment
Quality is not an afterthought; it is built-in:
*   **Unit Tests**: Every UseCase and Mapper MUST have 100% branch coverage. Use `MockK` or `Turbine` for Flow testing.
*   **Domain Isolation**: UseCases must be tested without any Android dependency (using `Mockk` for Repository interfaces).
*   **Integration Tests**: Test the Room Database and DataStore consistency in the `androidTest` source set.

---

## 7. Explicit Mapping & Scalability Architecture
To prevent "Model Leaking" (where local DB changes break-remote UI), we enforce **Strict DTO-to-Domain isolation**:
*   **No Entity Leaks**: Never pass a Room `@Entity` to the UI layer. Convert to a Domain model first.
*   **No Payload Leaks**: JSON DTOs from the AIDL bridge must be mapped to Domain Models inside the Data Layer.
*   **Transformation Logic**: Mappers must be kept in the `data/mapper` package and remain pure, deterministic functions.

---

## 8. Readability & Documentation (Elite Standards)
*   **KDoc Everything**: All public interfaces, classes, and UseCase `invoke()` methods must have KDoc explaining the *why*, not just the *what*.
*   **Explicit Returns**: Always specify the return type of a public function. Do not rely on Kotlin type inference for the public API surface.
*   **Meaningful Naming**: Avoid abbreviations. Use `processedReceiptCount` instead of `pc`.

---

## 9. Zero Downtime & Resilience
*   **Sticky Foreground Services**: Ensure the Android OS does not kill the server by maintaining a persistent notification.
*   **The Loop Safety Guard**: All asynchronous loops (Queue Dispatchers) must be wrapped in `try-catch` *within* the loop block to prevent the entire thread from dying due to a single malformed payload.
*   **Storage Sentinel**: Implement proactive disk-space checks to avoid DB corruption.
