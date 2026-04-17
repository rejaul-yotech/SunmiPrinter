package com.yotech.valtprinter.domain.repository

/**
 * Composite repository implemented by [com.yotech.valtprinter.data.repository.PrinterRepositoryImpl].
 * Callers inside the SDK that need only one capability should depend on the specific
 * sub-interface (`ScanRepository`, `ConnectionRepository`, …) instead of this composite.
 *
 * Host apps do **not** receive a reference to this type — [com.yotech.valtprinter.sdk.ValtPrinterSdk]
 * exposes narrow, delegating methods one capability at a time to avoid Law-of-Demeter leaks.
 *
 * [RenderRepository] is intentionally excluded from this composite: it is SDK-internal plumbing
 * consumed only by [com.yotech.valtprinter.data.queue.QueueDispatcher] and must not surface
 * anywhere in the public AAR API.
 */
interface PrinterRepository :
    ScanRepository,
    ConnectionRepository,
    PrintJobRepository,
    HardwareInfoRepository
