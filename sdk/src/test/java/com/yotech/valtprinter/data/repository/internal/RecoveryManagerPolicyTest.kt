package com.yotech.valtprinter.data.repository.internal

import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.core.util.FeedbackManager
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [RecoveryManager]'s synchronous policy:
 *   - manual-disconnect short-circuit
 *   - cooldown gate (per-device)
 *   - dedup gate (already-running for same device)
 *   - state bookkeeping (`isRecovering`, `activeRecoveryDeviceId`,
 *     `recoverySessionId`, `lastConnectedDevice`, `btConsecutiveMisses` reset)
 *   - prior connection snapshot is released and the LAN session is closed
 *     before a new recovery session starts
 *   - `cancel()` clears the recovery flags
 *
 * The actual reconnection loop body invokes `SunmiPrinterManager.getInstance()`
 * which is unavailable under pure-JVM tests. We therefore drive the manager
 * through a [StandardTestDispatcher] [TestScope] that we never advance — the
 * loop is launched, but its body never runs. All synchronous mutations
 * (cooldown timestamps, state flags) happen *before* the launch and are
 * therefore observable. Loop-body coverage is deferred until a Sunmi-search
 * seam is introduced (see follow-up task).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryManagerPolicyTest {

    private lateinit var state: ConnectionState
    private lateinit var coordinator: Coordinator
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var manager: RecoveryManager
    private lateinit var testScope: TestScope

    private val btDevice = PrinterDevice(
        id = "BT-AA:BB:CC:DD:EE:FF",
        name = "BT Printer",
        address = "AA:BB:CC:DD:EE:FF",
        port = 0,
        connectionType = ConnectionType.BLUETOOTH
    )
    private val usbDevice = PrinterDevice(
        id = "USB-1155-22336",
        name = "USB Printer",
        address = "",
        port = 0,
        connectionType = ConnectionType.USB
    )

    @Before
    fun setUp() {
        state = ConnectionState()
        feedbackManager = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        // Use a dispatcher we never advance — the launched recovery loop is
        // created but its body (which would call into SunmiPrinterManager)
        // does not execute.
        testScope = TestScope(StandardTestDispatcher())

        every { coordinator.state } returns state
        every { coordinator.scope } returns testScope
        every { coordinator.feedbackManager } returns feedbackManager
        every { coordinator.printerStateFlow } returns
            MutableStateFlow<PrinterState>(PrinterState.Idle)

        manager = RecoveryManager(coordinator)
    }

    @After
    fun tearDown() {
        // The loop body never runs, but the launched job lives on the
        // unscheduled dispatcher — cancel cleanly.
        manager.cancel()
    }

    // --- short-circuit gates -------------------------------------------------

    @Test
    fun `request is a no-op when isManualDisconnect is set`() = runTest {
        state.isManualDisconnect = true

        manager.request(btDevice, RecoveryReason.SDK_DISCONNECT, "manual teardown")

        assertFalse(state.isRecovering)
        assertNull(state.activeRecoveryDeviceId)
        assertEquals(0L, state.recoverySessionId)
    }

    @Test
    fun `duplicate request for the same device while already recovering is dropped`() {
        manager.request(btDevice, RecoveryReason.HEARTBEAT_LOSS, "first")
        val sessionAfterFirst = state.recoverySessionId
        val tsAfterFirst = state.lastRecoveryRequestMs

        manager.request(btDevice, RecoveryReason.HEARTBEAT_LOSS, "second")

        assertEquals(
            "Session id must NOT advance for a duplicate same-device trigger",
            sessionAfterFirst,
            state.recoverySessionId
        )
        assertEquals(
            "Cooldown timestamp must NOT advance for a duplicate trigger",
            tsAfterFirst,
            state.lastRecoveryRequestMs
        )
    }

    @Test
    fun `request for a DIFFERENT device starts a new session even while recovering`() {
        manager.request(btDevice, RecoveryReason.HEARTBEAT_LOSS, "BT lost")
        val firstSession = state.recoverySessionId

        manager.request(usbDevice, RecoveryReason.SDK_DISCONNECT, "USB took over")

        assertNotEquals(
            "A different-device trigger must NOT be deduped — recovery should pivot",
            firstSession,
            state.recoverySessionId
        )
        assertEquals(usbDevice.id, state.activeRecoveryDeviceId)
    }

    // --- state bookkeeping ---------------------------------------------------

    @Test
    fun `request installs recovery flags synchronously`() {
        manager.request(btDevice, RecoveryReason.HEARTBEAT_LOSS, "probe miss")

        // Everything below is set synchronously inside launchLoop BEFORE the
        // coroutine launch. `state.lastConnectedDevice` is intentionally NOT
        // asserted here — it is set inside the launched body and so is only
        // observable once the dispatcher is advanced.
        assertTrue(state.isRecovering)
        assertEquals(btDevice.id, state.activeRecoveryDeviceId)
        assertEquals(
            "BT strike counter must reset when a new recovery session starts",
            0,
            state.btConsecutiveMisses
        )
        assertTrue("Recovery session id must advance from 0", state.recoverySessionId > 0L)
    }

    @Test
    fun `request releases the prior connection snapshot before searching`() {
        val priorPrinter = mockk<CloudPrinter>(relaxed = true)
        every { priorPrinter.release(any()) } just Runs
        state.setConnected(priorPrinter, btDevice)

        manager.request(btDevice, RecoveryReason.SDK_DISCONNECT, "lost")

        assertNull(
            "Prior connection snapshot must be cleared before searching",
            state.current
        )
        verify(exactly = 1) { priorPrinter.release(any()) }
    }

    @Test
    fun `request closes a stale LAN session before searching`() {
        val staleSession = mockk<com.yotech.valtprinter.data.source.RawSocketPrintSource.Session>(
            relaxed = true
        )
        state.lanSession = staleSession

        manager.request(btDevice, RecoveryReason.SDK_DISCONNECT, "lost")

        verify(exactly = 1) { staleSession.closeQuietly() }
        assertNull(state.lanSession)
    }

    // --- isActiveFor / cancel ------------------------------------------------

    @Test
    fun `isActiveFor returns true only for the device currently being recovered`() {
        manager.request(btDevice, RecoveryReason.HEARTBEAT_LOSS, "lost")

        assertTrue(manager.isActiveFor(btDevice.id))
        assertFalse(manager.isActiveFor(usbDevice.id))
        assertFalse(manager.isActiveFor("totally-unknown-id"))
    }

    @Test
    fun `cancel clears recovery flags and active device id`() {
        manager.request(btDevice, RecoveryReason.HEARTBEAT_LOSS, "lost")

        manager.cancel()

        assertFalse(state.isRecovering)
        assertNull(state.activeRecoveryDeviceId)
        assertFalse(manager.isActiveFor(btDevice.id))
    }
}
