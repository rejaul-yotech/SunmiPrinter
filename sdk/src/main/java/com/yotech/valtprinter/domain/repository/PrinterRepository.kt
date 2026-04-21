package com.yotech.valtprinter.domain.repository

/**
 * Composite repository implemented by [com.yotech.valtprinter.data.repository.PrinterRepositoryImpl].
 * Callers inside the SDK that need only one capability should depend on the specific
 * sub-interface ([ScanRepository], [ConnectionRepository], [PrintJobRepository],
 * [HardwareInfoRepository]) instead of this composite.
 *
 * Host apps do **not** receive a reference to this type — [com.yotech.valtprinter.sdk.ValtPrinterSdk]
 * exposes narrow, delegating methods one capability at a time to avoid Law-of-Demeter leaks.
 *
 * [RenderRepository] is intentionally excluded from this composite: it is SDK-internal plumbing
 * consumed only by [com.yotech.valtprinter.data.queue.QueueDispatcher] and must not surface
 * anywhere in the public AAR API.
 *
 * ---
 *
 * ## Why four sub-interfaces, not one flat repository
 *
 * The project's "no repository interface with more than 6 methods" rule forces
 * capabilities to be split by **reason to change**, not by convenience. The
 * four interfaces here each have a single collaborator in the impl:
 *
 * | Sub-interface            | Owning manager           | What drives change                  |
 * |--------------------------|--------------------------|-------------------------------------|
 * | [ScanRepository]         | `ScanController`         | discovery protocols (BT LE, mDNS…)  |
 * | [ConnectionRepository]   | `ConnectionController`   | transport handshake + USB promotion |
 * | [PrintJobRepository]     | `PrintPipeline`          | ESC/POS pipeline, slicing, cut      |
 * | [HardwareInfoRepository] | direct `UsbManager`/`BluetoothAdapter` queries | OS permission surface |
 *
 * A future maintainer tempted to merge these (e.g. "`connect` and `printChunk`
 * both touch the printer, put them together") would break unit-testability —
 * each manager is isolated behind one of these interfaces so it can be
 * mocked individually. **Do not collapse.**
 *
 * ---
 *
 * ## Transport-priority contract
 *
 * Three transports are supported: **USB, LAN, Bluetooth.** They are not
 * equivalent — each has a distinct reliability and latency profile that the
 * connection lifecycle is tuned for.
 *
 * ```
 *                 preferred ───────────────▶ fallback
 *        ┌──────┐        ┌──────┐         ┌─────────────┐
 *        │ USB  │   ≻    │ LAN  │    ≻    │  Bluetooth  │
 *        └──────┘        └──────┘         └─────────────┘
 *     hardwired        routable          battery-friendly
 *     low-latency      high-latency      lossy, latency spikes
 *     auto-promote     atomic session    3-strike heartbeat
 * ```
 *
 * The priority is enforced by two mechanisms, not one:
 *
 * 1. **USB hot-plug takes over.** A `UsbAttachReceiver` calls
 *    [ConnectionRepository.promoteToUsb], which tears down any active
 *    non-USB session and reconnects over USB. That is why [ConnectionRepository.promoteToUsb]
 *    lives beside [ConnectionRepository.connect] rather than in a "UsbRepository"
 *    of its own: promotion *is* a connection lifecycle event, just one driven
 *    by the OS broadcast instead of a user tap. The previously-active
 *    non-USB device is remembered so that [ConnectionRepository.onUsbDetached]
 *    can bounce back to it on unplug.
 *
 * 2. **The host chooses the initial transport.** For BT/LAN the host scans
 *    via [ScanRepository], the user picks a device, [ConnectionRepository.connect]
 *    runs the handshake. USB is not user-selectable from scan results; it is
 *    reached only via [ConnectionRepository.autoConnectUsb] (startup path) or
 *    `promoteToUsb` (hot-plug path).
 *
 * Callers MUST NOT reorder these steps to, say, open a print job before
 * `connect` has transitioned [ConnectionRepository.printerState] to
 * `PrinterState.Connected`. [PrintJobRepository.isPrinterReady] is the
 * gate — the `QueueDispatcher` checks it every tick and parks the queue
 * if it returns false.
 *
 * ---
 *
 * ## Transport-specific behaviour that readers should know
 *
 * Some [PrintJobRepository] methods behave differently per transport — this
 * is deliberate and drives the split between `initPrintJob` / `printChunk`
 * / `finalCut`:
 *
 * - **LAN** uses a raw TCP session. `initPrintJob` opens the socket;
 *   `printChunk` streams ESC/POS bytes directly; `finalCut` writes the
 *   cut command and closes the socket. A mid-stream failure **cannot be
 *   resumed** — the printer has already advanced through whatever bytes
 *   arrived before the drop — so the dispatcher restarts the job from chunk 0.
 *   [ConnectionRepository.activeConnectionType] is the flag that lets the
 *   dispatcher apply this policy.
 * - **USB / BT** use the Sunmi SDK's transaction buffer. `initPrintJob`
 *   clears the buffer + sets backfeed; each `printChunk` appends; `finalCut`
 *   issues one atomic `commitTransBuffer`. A mid-job failure can be retried
 *   by the dispatcher without restarting from chunk 0 as long as the buffer
 *   has not been committed.
 *
 * The BT heartbeat additionally uses a 3-strike confirmation gate
 * (managed by `HeartbeatManager`) because BT probes have ~5–10% false-miss
 * rate on Sunmi hardware; USB and LAN are single-strike because their
 * transports are deterministic enough to trust the first miss.
 */
interface PrinterRepository :
    ScanRepository,
    ConnectionRepository,
    PrintJobRepository,
    HardwareInfoRepository
