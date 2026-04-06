# ValtPrinter Development Tracker

This document tracks the execution progress of the ValtPrinter "Gold Standard" project. As features and technologies are integrated into the codebase, they will be checked off.

## 1. Technologies & Architecture Foundation
- `[x]` **Clean Architecture Structure**: Domain, Data, UI modules implemented.
- `[x]` **SOLID Principles Enforced**: Dependency injection and interface-based repository pattern.
- `[x]` **Jetpack Compose Integration**: Implemented for both the Dashboard and Receipt Template rendering system.
- `[x]` **MVVM Pattern**: ViewModels decoupling State from UI.
- `[x]` **Kotlin Coroutines & Flow**: Used for non-blocking asynchronous processing and state observation.
- `[x]` **Dependency Injection (Hilt/Dagger)**: Full dependency graph management.
- `[ ]` **Persistence Layer (Room/DataStore)**: Persistent queue and settings management.

## 2. Phase 1: IPC & Data Governance (The Foundation)
- `[ ]` **AIDL Interface Configuration (`IPrinterService`)**: Build the multi-threaded bridge for Link App requests.
- `[ ]` **Room Database Initialization**: Create `PrintQueue` and `PrintLog` entities with `Job_ID`, `Priority`, and `JSON_Payload`.
- `[ ]` **The Handshake Protocol**: Logic returning the `TransactionToken` back to the Link App immediately upon saving to Room DB.
- `[ ]` **DataStore Configuration**: Set up User Preferences for TTL Intervals (1 week, 2 weeks, never, etc.).

## 3. Phase 2: The Concurrency Engine (Zero-Loss Queue)
- `[ ]` **The `QueueDispatcher` Background Worker**: Infinite coroutine loop reading Room DB.
- `[ ]` **Priority Algorithm SQL Validation**: Ensure Priority items jump instantly to the absolute next slot.
- `[ ]` **Sticky Foreground Service**: Persistent servant process to prevent OS process reclaiming.

## 4. Phase 3: Hardware Integration & Safe Rendering
- `[x]` **Sunmi SDK Implementation**: Core integration with Sunmi External Printer Library.
- `[x]` **Headless Compose Rendering Engine**: `BitmapRenderer` implemented to snapshot Compose UI off-screen.
- `[ ]` **JSON Payload Model Parser**: Domain code to translate raw Link App JSON into `BillingData` or generic templates.
- `[ ]` **OOM & Chunking Loop Protocol**: Logic to split long receipts into bite-sized Bitmaps incrementally.

## 5. Phase 4: Resilience & Auto-Healing
- `[ ]` **Hardware Disaster Rules (Paper/Power)**: Halt `QueueDispatcher` on fail codes, preserve DB State.
- `[ ]` **Resumption Hook**: Auto-restart logic upon Boot or upon Sunmi Paper Sensor Refill trigger.
- `[ ]` **The Double-Approval Broadcast**: Real-time exact success callbacks to the Link App.

## 6. Phase 5: The Control Center & Background Work
- `[/]` **Control Center UI**: Dashboard exists, but needs integration with the live Room queue.
- `[ ]` **Alarm Override System**: Audio/Visual high-priority alerts for hardware emergencies.
- `[ ]` **WorkManager Automation**: TTL Garbage Collection and Storage Sentinel tasks.
