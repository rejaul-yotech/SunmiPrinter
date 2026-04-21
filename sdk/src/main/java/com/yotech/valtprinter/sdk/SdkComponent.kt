package com.yotech.valtprinter.sdk

import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import com.yotech.valtprinter.data.queue.QueueDispatcher
import com.yotech.valtprinter.domain.repository.PrinterRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Narrow, typed surface of SDK-owned dependencies that manifest-instantiated
 * Android components (services, broadcast receivers) need at runtime.
 *
 * ## Why this type exists
 *
 * Android creates [android.content.BroadcastReceiver] and
 * [android.app.Service] subclasses reflectively via their no-arg constructor.
 * Constructor injection is therefore impossible — these components **must**
 * reach for their dependencies via a service locator. Without a typed
 * boundary, each component reaches through ad-hoc paths on [ValtPrinterSdk]
 * ("give me the repository impl", "give me the queue dispatcher's scope",
 * "give me the DAO"), which leaks concrete types and couples every receiver
 * to every internal field of the SDK.
 *
 * [SdkComponent] is the compile-time contract for that reach-through: one
 * lookup via [ValtPrinterSdk.component], one destructure, interface-typed
 * fields only. Adding a new dependency that receivers need means adding a
 * property here — a change that is visible to every consumer in one diff.
 *
 * ## What belongs here (and what does not)
 *
 * - **IN**: things a manifest-declared component needs to do its job.
 *   Example: the printer repository (for USB promotion), a dedicated coroutine
 *   scope for async work started from `onReceive`, the datastore.
 * - **OUT**: host-facing capabilities. Those belong on [ValtPrinterSdk]
 *   as public methods. If a field ends up in both places, the receiver is
 *   probably doing UI work — move it to the host.
 *
 * ## Why a dedicated [asyncScope] instead of reusing QueueDispatcher's
 *
 * The queue dispatcher owns its own IO scope for the print loop. A USB
 * attach broadcast firing `promoteToUsb` is a *different* lifecycle and
 * should not share cancellation semantics with the print loop — cancelling
 * the dispatcher to restart it (hypothetical future feature) would otherwise
 * also cancel any in-flight USB promotion. [asyncScope] is a supervisor
 * scope rooted at the SDK singleton, lives as long as the process, and is
 * appropriate for fire-and-forget work launched from broadcast receivers.
 */
internal data class SdkComponent(
    /**
     * The composite [PrinterRepository] interface — never the concrete impl.
     * Manifest components MUST NOT downcast. If a capability is missing here,
     * add it to one of the sub-interfaces rather than leaking the impl.
     */
    val printerRepository: PrinterRepository,

    /** The background queue driver. Held so the service can start/stop it. */
    val queueDispatcher: QueueDispatcher,

    /** Persistent printer settings (pause state, virtual roll height, …). */
    val printerDataStore: PrinterDataStore,

    /**
     * Supervisor scope for fire-and-forget work started from manifest-declared
     * components. See the class-level KDoc for why this is separate from the
     * dispatcher's scope.
     */
    val asyncScope: CoroutineScope
)
