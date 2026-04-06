# ValtPrinter "Gold Standard" - Elite Coding Rules & Guidelines

## 1. Architectural Integrity (Clean Architecture)
The application MUST strictly adhere to **Clean Architecture** patterns to ensure testability, scalability, and decoupling from external frameworks.

### 1.1 Layer Separation
*   **Domain Layer**: MUST be pure Kotlin. Zero dependencies on Android SDK, Room, or Third-party UI libraries. Holds all business logic (UseCases), entities, and repository interfaces.
*   **Data Layer**: Responsible for fetching and persisting data. Implements repositories. Contains DTOs (Data Transfer Objects), Mappers, and Sources (Room, Retrofit, Sunmi SDK).
*   **UI/Presentation Layer**: Use **Jetpack Compose** only. ViewModels handle state and interact with UseCases. No direct repository access from the UI.

### 1.2 Data Flow Rule
*   Data MUST flow unidirectionally: UI -> ViewModel -> UseCase -> Repository.
*   Use **StateFlow** for UI state and **SharedFlow** for single-shot events (Toasts, Navigation).

---

## 2. SOLID Principles & Elite Best Practices
Every class and function must strive for excellence through these rules:

*   **S (Single Responsibility)**: A UseCase must do exactly ONE thing (e.g., `GetActivePrintJob`). A Repository handles exactly ONE data source type.
*   **O (Open/Closed)**: Use interfaces for all dependencies. New functionality (e.g., adding a new printer brand) should be done via new implementation classes, not by modifying existing ones.
*   **D (Dependency Inversion)**: Always inject dependencies via Constructor. Use **Hilt** to manage the dependency graph.

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

## 6. Naming Conventions & Code Style
*   **Mappers**: `ToDomain()`, `ToEntity()`, `ToDto()`.
*   **UseCases**: Named as verbs: `ProcessPrintQueueUseCase`.
*   **UI States**: Named as `[Feature]UiState`. 
*   **Constants**: Use `object` or `companion object` with `const val UPPER_SNAKE_CASE`.

## 7. Zero Downtime Commitment
*   Implement **Sticky Foreground Services** to ensure Android does not kill the server.
*   All asynchronous loops (Queue Dispatchers) must be wrapped in `try-catch` within the loop to prevent the entire thread from dying due to a single bad payload.
