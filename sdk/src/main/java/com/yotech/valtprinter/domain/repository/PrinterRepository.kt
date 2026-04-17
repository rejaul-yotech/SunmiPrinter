package com.yotech.valtprinter.domain.repository

/**
 * Composite public repository — the surface that [ValtPrinterSdk.printerRepository] exposes to
 * host apps. Callers needing only one capability should depend on the specific sub-interface.
 *
 * [RenderRepository] is intentionally excluded: it is SDK-internal plumbing consumed only by
 * [QueueDispatcher] and must not surface in the public AAR API.
 */
interface PrinterRepository :
    ScanRepository,
    ConnectionRepository,
    PrintJobRepository,
    HardwareInfoRepository
