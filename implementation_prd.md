# ValtPrinter "Gold Standard" - Implementation PRD

## 1. Vision & Mission Statement
**ValtPrinter** is an enterprise-grade Android "Print Server" designed for high-concurrency F&B and Retail environments. Its primary goal is **Zero-Loss Reliability**: ensuring that every print request from the Valt Ecosystem is captured, persisted, rendered, and printed—even in cases of hardware failure, paper exhaustion, or power loss.

---

## 2. Current System State (Phase 0: Foundation)
The application currently features a robust modern Android foundation:
*   **Architecture**: Clean Architecture (Domain, Data, UI) with SOLID principles.
*   **UI Engine**: 100% Jetpack Compose for the Dashboard and Receipt Templates.
*   **Rendering**: Specialized `BitmapRenderer` for off-screen "Headless" Compose-to-Bitmap conversion.
*   **Hardware Integration**: Sunmi External Printer SDK and Raw Socket (TCP/IP) support.
*   **DI**: Hilt for dependency management.

---

## 3. Technical Specifications: Achieved

### 3.1 Headless Rendering Engine (`BitmapRenderer.kt`)
*   **Mechanism**: Uses a `ComposeView` created programmatically, measured at exactly 576 pixels (standard 80mm width), and snapshotted to a Bitmap.
*   **Optimization**: Executes on `Dispatchers.Main` for UI composition, with Bitmap creation on `Dispatchers.Default` to prevent UI thread jank.

### 3.2 Dynamic Receipt Templating
*   **Models**: Structured `BillingData` and `OrderItem` domain models.
*   **UI**: Modular Compose functions (`RestaurantHeader`, `PosPrintingScreen`) providing a premium "What You See Is What You Get" (WYSIWYG) receipt experience.

---

## 4. Technical Specifications: Scheduled (The "Server" Core)

### 4.1 Persistence Layer (Zero-Loss Queue)
*   **Technology**: Room Persistence Library.
*   **Entities**: 
    *   `PrintJob`: Tracks pending and active tasks (JobID, Priority, JSON Payload, Status).
    *   `PrintLogs`: Historical record of all prints with TTL (Time-To-Live) management.
*   **Logic**: Every incoming request is saved to Room **before** the caller receives an acknowledgment.

### 4.2 IPC Service (AIDL Bridge)
*   **Protocol**: `IPrinterService.aidl`.
*   **Function**: Provides a multi-threaded entry point for the "Link App" to submit JSON payloads.
*   **Status Handshake**: Returns a `TransactionToken` immediately after safe DB persistence.

### 4.3 Resilience & Concurrency Engine
*   **QueueDispatcher**: A persistent Kotlin Coroutine worker that polls the Room DB.
*   **Priority Rule**: SQL-level priority sorting ensures "Express Lane" jobs (e.g., KDS tickets) jump to the next available slot.
*   **Disaster Recovery**:
    *   **Paper-Out**: Detects hardware error codes via Sunmi SDK, pauses the queue, and auto-resumes once the sensor detects a new roll.
    *   **Power Failure**: Upon system reboot (`BOOT_COMPLETED`), the app auto-launches a **Sticky Foreground Service** and picks up unfinished jobs from the Room DB.

### 4.4 Data Governance & Cleanup
*   **Log TTL**: User-configurable log retention (1 week to 1 year).
*   **WorkManager**: Automated daily clearing of obsolete logs.
*   **Storage Sentinel**: Emergency logic to prevent "Disk Full" errors by pruning logs when storage is >95% full.

---

## 5. UI/UX: The Control Dashboard
While primarily a background servant, the app provides a premium management interface:
*   **Real-time Visibility**: Live view of the pending print queue.
*   **Hardware Health**: Visual status of the Sunmi printer (Connection, Paper status).
*   **Settings**: Toggles for log retention, startup behavior, and visual testing.

---

## 6. Implementation Roadmap
1.  **Phase 1**: Implement Room DB and AIDL Bridge (The persistence and inlet).
2.  **Phase 2**: Build the `QueueDispatcher` and Foreground Service (The heart of the server).
3.  **Phase 3**: Integrate real-time hardware error handling (Resilience).
4.  **Phase 4**: Add WorkManager for housekeeping and the Control Center UI (Governance).
