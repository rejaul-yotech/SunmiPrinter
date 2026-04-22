package com.yotech.valtprinter.data.repository.internal

import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [UsbTakeoverController.onUsbDetached] — the audit-flagged
 * "USB detach → fallback BT" path that was previously unverified
 * (improvement #10 from the audit).
 *
 * The promotion / auto-connect path is intentionally NOT covered here: it
 * touches `SunmiPrinterManager.getInstance()` and `Context.getSystemService`
 * which both require Robolectric or a true instrumented runner. The detach
 * policy, in contrast, is pure decision logic against [ConnectionState] +
 * the [Coordinator] / [ConnectionController] seams — pinning it under
 * pure-JVM unit tests gives us the safety net the audit asked for without
 * pulling in heavier infra.
 *
 * Contract pinned by these tests:
 *  - No-op when nothing is connected.
 *  - No-op when the active connection is NOT USB (defensive — the receiver
 *    is registered for USB DETACHED, but we still gate on transport type).
 *  - On USB detach with a remembered fallback: disconnect → recovery
 *    request for the fallback → `preferredFallbackDevice` is cleared so a
 *    second detach event cannot accidentally re-trigger the same recovery.
 *  - On USB detach without a fallback: disconnect only (no recovery
 *    request fires; we just sit Idle).
 */
class UsbTakeoverControllerTest {

    private lateinit var state: ConnectionState
    private lateinit var coordinator: Coordinator
    private lateinit var connectionController: ConnectionController
    private lateinit var scanController: ScanController
    private lateinit var controller: UsbTakeoverController

    private val usbDevice = PrinterDevice(
        id = "usb-001",
        name = "USB Printer",
        address = "",
        port = 0,
        connectionType = ConnectionType.USB
    )
    private val btFallback = PrinterDevice(
        id = "AA:BB:CC:DD:EE:FF",
        name = "BT Printer",
        address = "AA:BB:CC:DD:EE:FF",
        port = 0,
        connectionType = ConnectionType.BLUETOOTH
    )

    @Before
    fun setUp() {
        state = ConnectionState()
        coordinator = mockk(relaxed = true)
        connectionController = mockk(relaxed = true)
        scanController = mockk(relaxed = true)

        every { coordinator.state } returns state
        every { coordinator.printerStateFlow } returns MutableStateFlow<PrinterState>(PrinterState.Idle)
        every { connectionController.disconnect() } just Runs

        controller = UsbTakeoverController(coordinator, connectionController, scanController)
    }

    @Test
    fun `no-op when no device is connected`() = runTest {
        controller.onUsbDetached()

        verify(exactly = 0) { connectionController.disconnect() }
        verify(exactly = 0) { coordinator.requestRecovery(any(), any(), any()) }
    }

    @Test
    fun `no-op when the active connection is not USB`() = runTest {
        // Defensive gate: even if we somehow get a USB-detach signal while on BT,
        // we must not tear down the BT session.
        state.setConnected(mockk<CloudPrinter>(relaxed = true), btFallback)

        controller.onUsbDetached()

        verify(exactly = 0) { connectionController.disconnect() }
        verify(exactly = 0) { coordinator.requestRecovery(any(), any(), any()) }
    }

    @Test
    fun `USB detach with BT fallback disconnects and recovers to BT`() = runTest {
        state.setConnected(mockk<CloudPrinter>(relaxed = true), usbDevice)
        state.preferredFallbackDevice = btFallback
        state.isManualDisconnect = true // simulate a stale flag from a prior op

        controller.onUsbDetached()

        verify(exactly = 1) { connectionController.disconnect() }
        verify(exactly = 1) {
            coordinator.requestRecovery(
                device = btFallback,
                reason = RecoveryReason.SDK_DISCONNECT,
                details = any()
            )
        }
        // Cleared so a duplicate DETACHED broadcast cannot re-fire recovery.
        assertNull(
            "preferredFallbackDevice must be cleared after handing off to recovery",
            state.preferredFallbackDevice
        )
        // Recovery handshake must be allowed to proceed — the manual-disconnect
        // gate would otherwise suppress the SDK callback.
        assertFalse(
            "isManualDisconnect must be cleared so the BT reconnect is treated as a normal lifecycle",
            state.isManualDisconnect
        )
    }

    @Test
    fun `USB detach without a fallback disconnects but does not request recovery`() = runTest {
        state.setConnected(mockk<CloudPrinter>(relaxed = true), usbDevice)
        // No preferredFallbackDevice — e.g. the USB printer was the original
        // connection, never a takeover.

        controller.onUsbDetached()

        verify(exactly = 1) { connectionController.disconnect() }
        verify(exactly = 0) { coordinator.requestRecovery(any(), any(), any()) }
        assertNull(state.preferredFallbackDevice)
    }
}
