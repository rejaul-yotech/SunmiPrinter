package com.yotech.valtprinter.data.repository.internal

import android.graphics.Bitmap
import android.util.Log
import com.yotech.valtprinter.data.source.RawSocketPrintSource
import com.yotech.valtprinter.data.source.SdkPrintSource
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrintResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates a single print job across transports.
 *
 * **Contract:**
 * 1. [initJob] — opens transport state: LAN opens a single TCP session; USB/BT
 *    clears the SDK's transaction buffer.
 * 2. [printChunk] — appends one bitmap slice. Never cuts. Holds [printMutex]
 *    so concurrent callers serialize; the heartbeat respects this lock to
 *    avoid false "disconnected" readings during long prints.
 * 3. [finalCut] — delivers exactly one feed+cut per job and closes the LAN
 *    socket. Must be called even when the job was empty so transport state
 *    is released.
 *
 * Any transport-loss failure ([TransportErrorClassifier]) triggers recovery
 * via [Coordinator.requestRecovery].
 */
internal class PrintPipeline(
    private val coordinator: Coordinator,
    private val sdkPrintSource: SdkPrintSource,
    private val rawSocketPrintSource: RawSocketPrintSource,
    private val printMutex: Mutex
) {
    private val state: ConnectionState get() = coordinator.state

    suspend fun initJob(): PrintResult {
        val printer = state.activeCloudPrinter
            ?: return PrintResult.Failure("Not connected")
        val device = state.connectedDevice
            ?: return PrintResult.Failure("No active device")

        // LAN: open ONE TCP session for the entire job. printChunk appends to it,
        // finalCut delivers feed+cut and closes it. This is what makes a multi-
        // chunk LAN receipt produce ONE physical receipt with ONE cut at the bottom.
        if (device.connectionType == ConnectionType.LAN && device.address.isNotEmpty()) {
            // Defensive: tear down any stale session before opening a new one.
            state.lanSession?.closeQuietly()
            state.lanSession = null
            return when (val r = rawSocketPrintSource.openJob(device.address, device.port)) {
                is RawSocketPrintSource.OpenResult.Ok -> {
                    state.lanSession = r.session
                    PrintResult.Success
                }
                is RawSocketPrintSource.OpenResult.Failure -> PrintResult.Failure(r.reason)
            }
        }

        // USB/BT: clear the SDK's internal command buffer before the first chunk
        // so no stale content from a previous incomplete job is committed.
        return if (sdkPrintSource.initBuffer(printer)) PrintResult.Success
        else PrintResult.Failure("Buffer init failed")
    }

    suspend fun printChunk(bitmap: Bitmap, isLastChunk: Boolean): PrintResult {
        val printer = state.activeCloudPrinter
        val device = state.connectedDevice

        if (printer == null || device == null) {
            state.lastConnectedDevice?.let {
                if (!state.isRecovering) {
                    Log.w(
                        "PRINTER_DEBUG",
                        "printChunk detected missing active connection. Triggering recovery."
                    )
                    coordinator.requestRecovery(
                        device = it,
                        reason = RecoveryReason.ACTIVE_PRINTER_MISSING,
                        details = "printChunk called with null activeCloudPrinter/device"
                    )
                }
            }
            return PrintResult.Failure("Not connected to any printer.")
        }

        return printMutex.withLock {
            state.lastPrintActivityMs = System.currentTimeMillis()

            val result = if (device.connectionType == ConnectionType.LAN &&
                device.address.isNotEmpty()
            ) {
                val session = state.lanSession
                if (session == null) {
                    PrintResult.Failure("LAN session not open — initPrintJob() must run first.")
                } else {
                    val r = rawSocketPrintSource.appendChunk(session, bitmap)
                    if (r is PrintResult.Failure) {
                        // appendChunk closes on error; drop our reference so the
                        // next job's initJob() opens a fresh socket.
                        state.lanSession = null
                    }
                    r
                }
            } else {
                sdkPrintSource.printBitmapChunk(printer, bitmap, isLastChunk)
            }

            state.lastPrintActivityMs = System.currentTimeMillis()
            if (result is PrintResult.Failure &&
                TransportErrorClassifier.isTransportLoss(result.reason) &&
                !state.isRecovering
            ) {
                Log.w(
                    "PRINTER_DEBUG",
                    "printChunk transport loss: ${result.reason}. Triggering recovery."
                )
                coordinator.requestRecovery(
                    device = device,
                    reason = RecoveryReason.PRINT_TRANSPORT_LOSS,
                    details = result.reason
                )
            }
            result
        }
    }

    suspend fun finalCut(): PrintResult {
        val printer = state.activeCloudPrinter
            ?: return PrintResult.Failure("Printer null on finalCut")
        val device = state.connectedDevice

        // LAN: deliver the single feed-and-cut for this job, then close the socket.
        // appendChunk() never cuts; commitAndCut() is the single per-job cut point.
        if (device?.connectionType == ConnectionType.LAN && !device.address.isNullOrEmpty()) {
            val session = state.lanSession
            return if (session == null) {
                Log.w("PRINTER_DEBUG", "finalCut: LAN session missing — nothing to cut.")
                PrintResult.Failure("LAN session missing on finalCut")
            } else {
                Log.d("PRINTER_DEBUG", "finalCut: LAN path — committing feed+cut and closing socket.")
                val r = rawSocketPrintSource.commitAndCut(session)
                state.lanSession = null
                if (r is PrintResult.Success) {
                    state.lastSuccessfulPrintCommitMs = System.currentTimeMillis()
                    state.lastPrintActivityMs = state.lastSuccessfulPrintCommitMs
                }
                r
            }
        }

        // USB/BT: all chunks are buffered. This single awaitable commitAndCut
        // delivers lineFeed + cutPaper + commitTransBuffer. We suspend until the
        // printer confirms receipt so the cut only happens AFTER the printer has
        // physically processed all buffered image data.
        Log.d("PRINTER_DEBUG", "finalCut: USB/BT path — committing buffer and cutting.")
        val result = sdkPrintSource.commitAndCut(printer)
        if (result is PrintResult.Success) {
            state.lastSuccessfulPrintCommitMs = System.currentTimeMillis()
            state.lastPrintActivityMs = state.lastSuccessfulPrintCommitMs
            state.btConsecutiveMisses = 0
        }
        return result
    }
}
