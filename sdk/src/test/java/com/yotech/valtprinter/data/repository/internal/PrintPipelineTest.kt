package com.yotech.valtprinter.data.repository.internal

import android.graphics.Bitmap
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.data.source.RawSocketPrintSource
import com.yotech.valtprinter.data.source.SdkPrintSource
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrintResult
import com.yotech.valtprinter.domain.model.PrinterDevice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [PrintPipeline] — the per-job orchestrator that owns
 * [PrintPipeline.initJob] / [PrintPipeline.printChunk] / [PrintPipeline.finalCut].
 *
 * Pinned contract:
 *  - `initJob`:
 *      • LAN: opens a fresh socket via [RawSocketPrintSource]; tears down a
 *        stale [ConnectionState.lanSession] first; success → session installed,
 *        failure → reason propagated.
 *      • USB/BT: delegates to [SdkPrintSource.initBuffer]; boolean → PrintResult.
 *      • No active connection → `Failure("Not connected")`.
 *  - `printChunk`:
 *      • LAN with no session → typed failure (initJob must run first).
 *      • LAN session error → session reference is dropped so the next job
 *        opens a fresh socket.
 *      • USB/BT → delegates to [SdkPrintSource.printBitmapChunk].
 *      • Missing snapshot → triggers `requestRecovery(ACTIVE_PRINTER_MISSING)`
 *        when a `lastConnectedDevice` is known and recovery isn't already running.
 *      • Transport-loss failure → triggers `requestRecovery(PRINT_TRANSPORT_LOSS)`.
 *      • Hardware failure (paper out etc.) → does NOT trigger recovery.
 *      • Activity timestamps are stamped on every call.
 *      • `printMutex` serializes overlapping callers.
 *  - `finalCut`:
 *      • USB/BT success → stamps `lastSuccessfulPrintCommitMs` and resets
 *        `btConsecutiveMisses` (the heartbeat's BT strike count).
 *      • LAN success → commits feed+cut, drops the session, stamps timestamps.
 *      • LAN with no session → typed failure.
 *      • No connection → `Failure("Printer null on finalCut")`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrintPipelineTest {

    private lateinit var state: ConnectionState
    private lateinit var coordinator: Coordinator
    private lateinit var sdkPrintSource: SdkPrintSource
    private lateinit var rawSocketPrintSource: RawSocketPrintSource
    private lateinit var printMutex: Mutex
    private lateinit var pipeline: PrintPipeline

    private val cloudPrinter = mockk<CloudPrinter>(relaxed = true)
    private val bitmap = mockk<Bitmap>(relaxed = true).also {
        every { it.height } returns 400
    }

    private val usbDevice = PrinterDevice(
        id = "USB-1155-22336",
        name = "USB Printer",
        address = "",
        port = 0,
        connectionType = ConnectionType.USB
    )
    private val btDevice = PrinterDevice(
        id = "BT-AA:BB:CC:DD:EE:FF",
        name = "BT Printer",
        address = "AA:BB:CC:DD:EE:FF",
        port = 0,
        connectionType = ConnectionType.BLUETOOTH
    )
    private val lanDevice = PrinterDevice(
        id = "LAN-192.168.1.50",
        name = "LAN Printer",
        address = "192.168.1.50",
        port = 9100,
        connectionType = ConnectionType.LAN
    )

    @Before
    fun setUp() {
        state = ConnectionState()
        coordinator = mockk(relaxed = true)
        sdkPrintSource = mockk(relaxed = true)
        rawSocketPrintSource = mockk(relaxed = true)
        printMutex = Mutex()

        every { coordinator.state } returns state
        pipeline = PrintPipeline(coordinator, sdkPrintSource, rawSocketPrintSource, printMutex)
    }

    // --- initJob -------------------------------------------------------------

    @Test
    fun `initJob returns Failure when no connection is active`() = runTest {
        val r = pipeline.initJob()
        assertEquals(PrintResult.Failure("Not connected"), r)
    }

    @Test
    fun `initJob on USB-BT delegates to SdkPrintSource initBuffer`() = runTest {
        state.setConnected(cloudPrinter, usbDevice)
        every { sdkPrintSource.initBuffer(cloudPrinter) } returns true

        val r = pipeline.initJob()

        assertEquals(PrintResult.Success, r)
        verify(exactly = 1) { sdkPrintSource.initBuffer(cloudPrinter) }
    }

    @Test
    fun `initJob on USB-BT propagates initBuffer failure`() = runTest {
        state.setConnected(cloudPrinter, btDevice)
        every { sdkPrintSource.initBuffer(cloudPrinter) } returns false

        val r = pipeline.initJob()

        assertEquals(PrintResult.Failure("Buffer init failed"), r)
    }

    @Test
    fun `initJob on LAN tears down a stale session before opening a new one`() = runTest {
        state.setConnected(cloudPrinter, lanDevice)
        val staleSession = mockk<RawSocketPrintSource.Session>(relaxed = true)
        state.lanSession = staleSession
        val freshSession = mockk<RawSocketPrintSource.Session>(relaxed = true)
        coEvery { rawSocketPrintSource.openJob(lanDevice.address, lanDevice.port) } returns
            RawSocketPrintSource.OpenResult.Ok(freshSession)

        val r = pipeline.initJob()

        assertEquals(PrintResult.Success, r)
        verify(exactly = 1) { staleSession.closeQuietly() }
        assertSame(freshSession, state.lanSession)
    }

    @Test
    fun `initJob on LAN propagates openJob failure with reason`() = runTest {
        state.setConnected(cloudPrinter, lanDevice)
        coEvery { rawSocketPrintSource.openJob(any(), any()) } returns
            RawSocketPrintSource.OpenResult.Failure("ECONNREFUSED")

        val r = pipeline.initJob()

        assertEquals(PrintResult.Failure("ECONNREFUSED"), r)
        assertNull("LAN session must remain unset on open failure", state.lanSession)
    }

    // --- printChunk ----------------------------------------------------------

    @Test
    fun `printChunk on USB-BT delegates to SdkPrintSource and stamps activity`() = runTest {
        state.setConnected(cloudPrinter, usbDevice)
        coEvery { sdkPrintSource.printBitmapChunk(cloudPrinter, bitmap, false) } returns
            PrintResult.Success
        val before = state.lastPrintActivityMs

        val r = pipeline.printChunk(bitmap, isLastChunk = false)

        assertEquals(PrintResult.Success, r)
        assertTrue(
            "lastPrintActivityMs must advance after a chunk write",
            state.lastPrintActivityMs > before
        )
        coVerify(exactly = 1) { sdkPrintSource.printBitmapChunk(cloudPrinter, bitmap, false) }
    }

    @Test
    fun `printChunk on LAN without an open session fails fast`() = runTest {
        state.setConnected(cloudPrinter, lanDevice)
        // No state.lanSession set — initPrintJob() was never called.

        val r = pipeline.printChunk(bitmap, isLastChunk = false)

        assertTrue(r is PrintResult.Failure)
        assertEquals(
            "LAN session not open — initPrintJob() must run first.",
            (r as PrintResult.Failure).reason
        )
    }

    @Test
    fun `printChunk on LAN clears the session reference on append failure`() = runTest {
        state.setConnected(cloudPrinter, lanDevice)
        val session = mockk<RawSocketPrintSource.Session>(relaxed = true)
        state.lanSession = session
        coEvery { rawSocketPrintSource.appendChunk(session, bitmap) } returns
            PrintResult.Failure("write: broken pipe")

        val r = pipeline.printChunk(bitmap, isLastChunk = false)

        assertTrue(r is PrintResult.Failure)
        assertNull(
            "Session reference must be dropped so the next job opens a fresh socket",
            state.lanSession
        )
    }

    @Test
    fun `printChunk with no snapshot triggers recovery when a last device is known`() = runTest {
        // Simulate "we were connected, then lost the snapshot mid-print"
        state.lastConnectedDevice = btDevice

        val r = pipeline.printChunk(bitmap, isLastChunk = false)

        assertEquals(PrintResult.Failure("Not connected to any printer."), r)
        verify(exactly = 1) {
            coordinator.requestRecovery(
                device = btDevice,
                reason = RecoveryReason.ACTIVE_PRINTER_MISSING,
                details = any()
            )
        }
    }

    @Test
    fun `printChunk does not double-trigger recovery when one is already running`() = runTest {
        state.lastConnectedDevice = btDevice
        state.isRecovering = true

        pipeline.printChunk(bitmap, isLastChunk = false)

        verify(exactly = 0) { coordinator.requestRecovery(any(), any(), any()) }
    }

    @Test
    fun `printChunk transport-loss failure triggers recovery for the active device`() = runTest {
        state.setConnected(cloudPrinter, btDevice)
        coEvery { sdkPrintSource.printBitmapChunk(cloudPrinter, bitmap, false) } returns
            PrintResult.Failure("Bluetooth disconnected unexpectedly")

        val r = pipeline.printChunk(bitmap, isLastChunk = false)

        assertTrue(r is PrintResult.Failure)
        verify(exactly = 1) {
            coordinator.requestRecovery(
                device = btDevice,
                reason = RecoveryReason.PRINT_TRANSPORT_LOSS,
                details = "Bluetooth disconnected unexpectedly"
            )
        }
    }

    @Test
    fun `printChunk hardware failure such as paper-out does NOT trigger recovery`() = runTest {
        state.setConnected(cloudPrinter, usbDevice)
        coEvery { sdkPrintSource.printBitmapChunk(cloudPrinter, bitmap, false) } returns
            PrintResult.Failure("Paper Out")

        pipeline.printChunk(bitmap, isLastChunk = false)

        verify(exactly = 0) { coordinator.requestRecovery(any(), any(), any()) }
    }

    @Test
    fun `printChunk serialises overlapping calls via printMutex`() = runTest(StandardTestDispatcher()) {
        state.setConnected(cloudPrinter, usbDevice)
        // First call suspends inside the mutex window — second call must wait.
        coEvery { sdkPrintSource.printBitmapChunk(cloudPrinter, bitmap, false) } coAnswers {
            delay(50)
            PrintResult.Success
        }

        val firstStart = currentTimeMs()
        val a = async { pipeline.printChunk(bitmap, false) }
        val b = async { pipeline.printChunk(bitmap, false) }
        advanceUntilIdle()
        a.await(); b.await()

        // Two suspending 50-ms writes serialised under the mutex must take
        // at least ~100 virtual ms — proves they did not run concurrently.
        assertTrue(
            "Two mutex-guarded writes should not overlap",
            currentTimeMs() - firstStart >= 100L
        )
        coVerify(exactly = 2) { sdkPrintSource.printBitmapChunk(cloudPrinter, bitmap, false) }
    }

    // --- finalCut ------------------------------------------------------------

    @Test
    fun `finalCut on USB-BT commits and stamps success bookkeeping`() = runTest {
        state.setConnected(cloudPrinter, usbDevice)
        state.btConsecutiveMisses = 2 // pretend the heartbeat had been struggling
        coEvery { sdkPrintSource.commitAndCut(cloudPrinter) } returns PrintResult.Success

        val r = pipeline.finalCut()

        assertEquals(PrintResult.Success, r)
        assertTrue(
            "lastSuccessfulPrintCommitMs must advance after a successful cut",
            state.lastSuccessfulPrintCommitMs > 0L
        )
        assertEquals(
            "A successful commit must reset the BT heartbeat strike counter",
            0,
            state.btConsecutiveMisses
        )
    }

    @Test
    fun `finalCut on LAN delivers feed-cut, drops session, stamps timestamps`() = runTest {
        state.setConnected(cloudPrinter, lanDevice)
        val session = mockk<RawSocketPrintSource.Session>(relaxed = true)
        state.lanSession = session
        coEvery { rawSocketPrintSource.commitAndCut(session) } returns PrintResult.Success

        val r = pipeline.finalCut()

        assertEquals(PrintResult.Success, r)
        assertNull("LAN session must be released after the cut", state.lanSession)
        assertTrue(state.lastSuccessfulPrintCommitMs > 0L)
        assertEquals(
            "lastPrintActivityMs is bumped to mirror the commit timestamp",
            state.lastSuccessfulPrintCommitMs,
            state.lastPrintActivityMs
        )
    }

    @Test
    fun `finalCut on LAN with no open session returns typed failure`() = runTest {
        state.setConnected(cloudPrinter, lanDevice)
        // No state.lanSession.

        val r = pipeline.finalCut()

        assertTrue(r is PrintResult.Failure)
        assertEquals(
            "LAN session missing on finalCut",
            (r as PrintResult.Failure).reason
        )
    }

    @Test
    fun `finalCut with no active connection returns typed failure`() = runTest {
        val r = pipeline.finalCut()
        assertEquals(PrintResult.Failure("Printer null on finalCut"), r)
    }

    @Test
    fun `finalCut LAN failure still drops the session so a stale socket cannot leak`() = runTest {
        state.setConnected(cloudPrinter, lanDevice)
        val session = mockk<RawSocketPrintSource.Session>(relaxed = true)
        state.lanSession = session
        coEvery { rawSocketPrintSource.commitAndCut(session) } returns
            PrintResult.Failure("write: broken pipe")

        val r = pipeline.finalCut()

        assertTrue(r is PrintResult.Failure)
        assertNull("Session must be released even when the cut fails", state.lanSession)
        assertNotNull(r)
    }

    // ------------------------------------------------------------------------

    /** Wall-clock helper for tests using [StandardTestDispatcher]. */
    private fun kotlinx.coroutines.test.TestScope.currentTimeMs(): Long =
        testScheduler.currentTime
}
