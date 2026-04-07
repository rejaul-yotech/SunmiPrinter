# ValtPrinter Development Tracker

This document tracks the execution progress of the ValtPrinter "Gold Standard" project. As features and technologies are integrated into the codebase, they will be checked off.

## 1. Technologies & Architecture Foundation
- `[x]` **Clean Architecture Structure**: Domain, Data, UI modules implemented.
- `[x]` **SOLID Principles Enforced**: Dependency injection and interface-based repository pattern.
- `[x]` **Jetpack Compose Integration**: Implemented for both the Dashboard and Receipt Template rendering system.
- `[x]` **MVVM Pattern**: ViewModels decoupling State from UI.
- `[x]` **Kotlin Coroutines & Flow**: Used for non-blocking asynchronous processing and state observation.
- `[x]` **Dependency Injection (Hilt/Dagger)**: Full dependency graph management.
- `[x]` **Persistence Layer (Room/DataStore)**: Persistent queue and settings management.
    - `[x]` Add Room & DataStore dependencies to `libs.versions.toml` and `build.gradle.kts`.
    - `[x]` Define `PrintJobEntity` for the Zero-Loss state machine.
    - `[x]` Implement `PrintDao` with priority-aware queries.
    - `[x]` Configure `PrinterDataStore` for paper height heuristics.

## 2. Phase 1: IPC & Data Governance (The Foundation)
- `[x]` **AIDL Interface Configuration (`IPrinterService`)**: Build the multi-threaded bridge for Link App requests.
- `[x]` **Room Database Initialization**: Create `PrintQueue` and `PrintLog` entities with `Job_ID`, `Priority`, and `JSON_Payload`.
- `[x]` **The Handshake Protocol**: Logic returning the `TransactionToken` back to the Link App immediately upon saving to Room DB.
- `[x]` **DataStore Configuration**: Set up User Preferences for TTL Intervals (1 week, 2 weeks, never, etc.).

## 3. Phase 2: The Concurrency Engine (Zero-Loss Queue)
- `[x]` **The `QueueDispatcher` Background Worker**: Infinite coroutine loop reading Room DB.
- `[x]` **Priority Algorithm SQL Validation**: Ensure Priority items jump instantly to the absolute next slot.
- `[x]` **Sticky Foreground Service**: Persistent servant process to prevent OS process reclaiming.

## 4. Phase 3: Hardware Integration & Safe Rendering
- `[x]` **Sunmi SDK Implementation**: Core integration with Sunmi External Printer Library.
- `[x]` **Headless Compose Rendering Engine**: `BitmapRenderer` implemented to snapshot Compose UI off-screen.
- `[x]` **JSON Payload Model Parser**: Domain code to translate raw Link App JSON into `BillingData` or generic templates.
- `[x]` **OOM & Chunking Loop Protocol**: Logic to split long receipts into bite-sized Bitmaps incrementally.

## 5. Phase 4: Resilience & Auto-Healing
- `[x]` **Hardware Disaster Rules (Paper/Power)**: Halt `QueueDispatcher` on fail codes, preserve DB State.
- `[x]` **Resumption Hook**: Auto-restart logic upon Boot or upon Sunmi Paper Sensor Refill trigger.
- `[x]` **The Double-Approval Broadcast**: Real-time exact success callbacks to the Link App.

## 6. Phase 5: The Control Center & Background Work
- `[x]` **Control Center UI**: Dashboard integrated with the live Room queue.
- `[x]` **Alarm Override System**: Audio/Visual high-priority alerts with Vibrate & Silence mechanics.
- `[x]` **WorkManager Automation**: TTL Garbage Collection and Storage Sentinel tasks.

## 7. Elite Architectural Refactoring
- `[x]` **StateFlow/SharedFlow Migration**: Upgraded ViewModel events to `SharedFlow` with `onBufferOverflow = DROP_OLDEST`.
- `[x]` **Mandatory Hilt Injection**: Verified `UseCase` and `Repository` level Dagger-Hilt injection compliance.
- `[x]` **Coding Rules Modernization**: Added zero-downtime rules and lifecycle-aware protocol to `CODING_RULES.md`.

## 8. Phase 6: Elite UI/UX Refinement
- `[x]` **Implement Unified Status Pill**: Merge the connectivity dot and status text into a single cohesive chip component.
- `[x]` **Adaptive Smart Button**: Refactor the Disconnect/Reconnect button to dynamically switch styles (Outlined/Filled) and colors (Red/Cyan) based on state.
- `[x]` **Discovery Cleanup**: Replace redundant "Scanning" text with a Linear Progress Bar and dynamic subtitles.
- `[x]` **Visual Polish**: Apply Glassmorphism effects and enhanced typography weights to the Printer Card.

## 9. Phase 7: Masterclass Resilience UX
- `[x]` **Silent Guardian Protocol**: Refactor `FeedbackManager` with escalating feedback (haptic first, tone after 5s). Modify reconnection loop to handle this timing.
- `[x]` **Progressive Transparency (Micro-states)**: Update `PrinterState` to support dynamic micro-state strings. Inject "Scanning...", "Handshake..." into UI during reconnection.
- `[x]` **Offline Queue Assurance**: Display pending job count and an assurance message in `Error` and `Reconnecting` states.
- `[x]` **Contextual Fallthrough Diagnostics**: Provide specific UI troubleshooting steps based on the original `ConnectionType` if recovery fails.

## 10. Phase 8: High-End UX & Automatic Restoration
- `[x]` **Pulsing Radar Animation**: Implement high-end visual feedback for the `Connecting` state.
- `[x]` **Resilience Pill Interaction**: Make the minimized Hub clickable to expand back to full view.
- `[x]` **Spelling Fix**: Corrected "DISSIMISS" to "DISMISS" in the Hub UI.
- `[x]` **Handshake Reliability**: Ensured automatic reconnection is seamless and handles silent handshake failures with a binding delay.
- `[x]` **Check Signal Now**: Integrated "Run Diagnostics" as a manual trigger for the recovery loop.

## 11. Phase 9: The Technical Guardian (Final Refinements)
- `[x]` **Refine Hub Buttons**: Replaced "Run Diagnostics" with "Rescan for Other Devices" to eliminate redundancy.
- `[x]` **Near-Instant Recovery**: Increased polling to 1s for the first 10s of any fault.
- `[x]` **Silent guardian**: Ensured all feedback is 100% haptic (confirmed removal of all audio triggers).
- `[x]` **Expand on Tap**: Verified minimized pill correctly triggers `expandHardwareHub()`.

## 12. Phase 10: Bulletproof LAN Restoration
- `[x]` **Concurrency Guard**: Prevented overlapping `connect()` handshakes during recovery using `isConnecting` flag.
- `[x]` **Extended LAN Delay**: Increased post-discovery delay to 3s for LAN TCP stabilization to resolve SocketTimeoutExceptions.
- `[x]` **Graceful Retries**: Handled socket timeouts silently during recovery, keeping the Hub persistent until success.

## 13. Phase 11: Perfecting USB/BT Quality
- `[x]` **Align to Left**: `setAlignment(AlignStyle.LEFT)` called in every `addToBuffer()` call.
- `[x]` **Advance Before Cut**: `commitAndCut()` feeds 6 lines (~18mm) before cutting — ensures last pixel clears the cutter blade on NT311.
- `[x]` **Seamless Chunking**: Refactored to proper SDK transaction model — all chunks are buffered without `commitTransBuffer`, a single atomic `commitAndCut` finalizes the receipt. Eliminates the race condition that caused premature paper cutting on USB/BT.

## 14. Phase 12: Critical Bug Fixes (Active — Needs Verification)

> **CURRENT STATUS**: Fixes applied. Build and test required.

### Bug A — LAN Auto-Disconnect/Reconnect Loop 🔴

**Symptom**: When connected via LAN and navigating between screens (e.g., PrinterScreen → ReceiptPreviewScreen → back), the printer auto-disconnects and immediately reconnects in a continuous loop. Visible in logcat as rapid `onDisConnect` → `triggerAutoReconnection` → `onConnect` cycles every ~3 seconds.

**Root Cause**: `checkPhysicalConnection()` for LAN was probing **port 9100 via raw TCP socket** every 3 seconds (the heartbeat). The Sunmi SDK uses port 9100 for its own internal protocol session. Our raw socket probe was **hijacking the SDK's byte stream** — the printer received unexpected data on the SDK session, force-closed it, and fired `onDisConnect()`. This triggered `triggerAutoReconnection()` which reconnected quickly, and 3 seconds later the probe fired again → infinite loop.

**Files Changed**:
- `PrinterRepositoryImpl.kt` → `checkPhysicalConnection()`: LAN case now returns `true` unconditionally. The SDK's own `onDisConnect()` callback is the reliable source of truth for real disconnects (e.g., cable pull, power off). Raw socket probes are permanently removed.

**Verification**: Connect via LAN. Navigate to preview screen and back multiple times. Printer state must remain `Connected` throughout. Heartbeat logcat should show `"LAN heartbeat: trusting SDK session"` every 3s with no reconnection messages.

---

### Bug B — USB Printing Cut Before Full Content Printed 🔴

**Symptom**: When printing via USB (or Bluetooth), the paper is cut before the entire receipt has printed. The remaining content appears at the top of the NEXT paper sheet.

**Root Cause — Layer 1 (Race Condition, fixed in Phase 11)**: `commitTransBuffer` was called after every 400px chunk. The SDK callback fires when data enters the kernel USB driver buffer — NOT when the printer head physically advances through those pixels. `finalCut()` was then called immediately after, sending the cut command before the printer finished mechanically processing the last chunk.

**Root Cause — Layer 2 (Stale Buffer, fixed in Phase 12)**: `clearTransBuffer()` was never called before a new print job. The SDK's internal buffer retains stale commands from any previous incomplete/failed job. These stale commands were being committed together with the new job content, causing corrupted output and off-sync line positions.

**Files Changed**:
- `SdkPrintSource.kt`:
  - New `initBuffer(printer)` → calls `printer.clearTransBuffer()` to clear the buffer
  - `printBitmapChunk(isLastChunk=true)` → calls `initBuffer` first before `addToBuffer`
  - `printBitmap()` → calls `initBuffer` first
- `PrinterRepository.kt` → Added `initPrintJob()` interface method
- `PrinterRepositoryImpl.kt` → `initPrintJob()` calls `sdkPrintSource.initBuffer()` for USB/BT; no-op for LAN
- `QueueDispatcher.kt` → Calls `printerRepository.initPrintJob()` before the chunk rendering loop

**Full Print Flow (USB/BT) After Fix**:
```
1. initPrintJob()        → printer.clearTransBuffer()   [clean slate]
2. printChunk(chunk0)    → printer.printImage(chunk0)  [buffer only, no commit]
3. printChunk(chunk1)    → printer.printImage(chunk1)  [buffer only, no commit]
4. finalCut()            → printer.lineFeed(6)
                           printer.cutPaper(true)
                           printer.commitTransBuffer()  [ONE atomic send, awaits hardware ACK]
                           ✅ Cut only after printer confirms all content received
```

**Verification**:
1. Connect via USB. Press "PREVIEW TICKET" → "PRINT TO SUNMI".
2. The FULL receipt must print before the paper is cut.
3. No content should appear on the next paper sheet.
4. Check logcat for `"Buffer initialized — clean slate for new job"` before each print.
5. Check logcat for `"commitAndCut: printer confirmed receipt ✔"` after successful cut.
