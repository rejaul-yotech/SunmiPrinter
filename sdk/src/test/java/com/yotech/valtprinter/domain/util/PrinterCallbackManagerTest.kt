package com.yotech.valtprinter.domain.util

import com.yotech.valtprinter.sdk.JobEvent
import com.yotech.valtprinter.sdk.PrintJobCallback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [PrinterCallbackManager]. Covers both surfaces:
 *  - the classic [PrintJobCallback] interface
 *  - the streaming [PrinterCallbackManager.jobEvents] `SharedFlow<JobEvent>`
 *
 * The contract we pin here:
 *  - `notifySuccess` fires both the callback AND a `Completed` flow event.
 *  - `notifyFailed` fires both the callback AND a `Failed` flow event.
 *  - Progress emits (`emitPrinting`) and interruptions (`emitInterrupted`)
 *    appear on the flow only — the classic callback deliberately doesn't
 *    receive these (it was designed for terminal outcomes only).
 *  - Null `externalJobId` is silently dropped on every emit path (legacy
 *    contract — some pre-typed-envelope payloads lack an id).
 *  - Events from different jobs are independently observable by filtering
 *    on [JobEvent.externalJobId].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrinterCallbackManagerTest {

    private lateinit var manager: PrinterCallbackManager

    @Before
    fun setUp() {
        manager = PrinterCallbackManager()
    }

    // --- Classic callback interface parity ------------------------------------

    @Test
    fun `notifySuccess fires registered callback`() {
        val received = mutableListOf<String>()
        manager.register(object : PrintJobCallback {
            override fun onJobSuccess(jobId: String) { received += jobId }
            override fun onJobFailed(jobId: String, reason: String) = Unit
        })

        manager.notifySuccess("job-1")

        assertEquals(listOf("job-1"), received)
    }

    @Test
    fun `notifyFailed fires registered callback with reason`() {
        val received = mutableListOf<Pair<String, String>>()
        manager.register(object : PrintJobCallback {
            override fun onJobSuccess(jobId: String) = Unit
            override fun onJobFailed(jobId: String, reason: String) {
                received += jobId to reason
            }
        })

        manager.notifyFailed("job-7", "Paper out")

        assertEquals(listOf("job-7" to "Paper out"), received)
    }

    @Test
    fun `unregister stops the callback from firing`() {
        val received = mutableListOf<String>()
        val cb = object : PrintJobCallback {
            override fun onJobSuccess(jobId: String) { received += jobId }
            override fun onJobFailed(jobId: String, reason: String) = Unit
        }
        manager.register(cb)
        manager.unregister(cb)

        manager.notifySuccess("job-after-unregister")

        assertTrue("Unregistered callback should not fire", received.isEmpty())
    }

    @Test
    fun `callback throwing does not break other listeners`() {
        val received = mutableListOf<String>()
        manager.register(object : PrintJobCallback {
            override fun onJobSuccess(jobId: String) = error("boom")
            override fun onJobFailed(jobId: String, reason: String) = Unit
        })
        manager.register(object : PrintJobCallback {
            override fun onJobSuccess(jobId: String) { received += jobId }
            override fun onJobFailed(jobId: String, reason: String) = Unit
        })

        manager.notifySuccess("job-42")

        assertEquals(listOf("job-42"), received)
    }

    // --- Null job id is silently dropped (legacy contract) --------------------

    @Test
    fun `null job id is silently dropped across all emit paths`() = runTest(UnconfinedTestDispatcher()) {
        val events = collectEvents(this)
        val cbSuccess = mutableListOf<String>()
        val cbFail = mutableListOf<String>()
        manager.register(object : PrintJobCallback {
            override fun onJobSuccess(jobId: String) { cbSuccess += jobId }
            override fun onJobFailed(jobId: String, reason: String) { cbFail += jobId }
        })

        manager.notifySuccess(null)
        manager.notifyFailed(null, "whatever")
        manager.emitPrinting(null, chunkIndex = 0, totalChunks = null)
        manager.emitInterrupted(null, "whatever")

        testScheduler.advanceUntilIdle()
        assertTrue(events.isEmpty())
        assertTrue(cbSuccess.isEmpty())
        assertTrue(cbFail.isEmpty())
    }

    // --- Flow emissions -------------------------------------------------------

    @Test
    fun `notifySuccess also emits Completed on the flow`() = runTest(UnconfinedTestDispatcher()) {
        val events = collectEvents(this)

        manager.notifySuccess("job-1")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(JobEvent.Completed("job-1")), events)
    }

    @Test
    fun `notifyFailed also emits Failed on the flow with reason`() = runTest(UnconfinedTestDispatcher()) {
        val events = collectEvents(this)

        manager.notifyFailed("job-1", "Cover open")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(JobEvent.Failed("job-1", "Cover open")), events)
    }

    @Test
    fun `emitEnqueued is flow-only (no callback analogue)`() = runTest(UnconfinedTestDispatcher()) {
        val events = collectEvents(this)
        val callbackCalls = mutableListOf<String>()
        manager.register(object : PrintJobCallback {
            override fun onJobSuccess(jobId: String) { callbackCalls += "success:$jobId" }
            override fun onJobFailed(jobId: String, reason: String) {
                callbackCalls += "fail:$jobId"
            }
        })

        manager.emitEnqueued("job-new")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(JobEvent.Enqueued("job-new")), events)
        assertTrue(callbackCalls.isEmpty())
    }

    @Test
    fun `emitPrinting is flow-only with chunk index`() = runTest(UnconfinedTestDispatcher()) {
        val events = collectEvents(this)

        manager.emitPrinting("job-9", chunkIndex = 3, totalChunks = null)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(JobEvent.Printing("job-9", chunkIndex = 3, totalChunks = null)),
            events
        )
    }

    @Test
    fun `emitInterrupted carries reason`() = runTest(UnconfinedTestDispatcher()) {
        val events = collectEvents(this)

        manager.emitInterrupted("job-9", "Transport loss")
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(JobEvent.Interrupted("job-9", "Transport loss")),
            events
        )
    }

    // --- Per-job correlation (the whole point of #9) --------------------------

    @Test
    fun `events from concurrent jobs are independently filterable by jobId`() = runTest(UnconfinedTestDispatcher()) {
        val events = collectEvents(this)

        // Interleave emissions for two jobs to mirror real dispatcher traffic.
        manager.emitEnqueued("ORDER-A")
        manager.emitEnqueued("ORDER-B")
        manager.emitPrinting("ORDER-A", chunkIndex = 1, totalChunks = null)
        manager.emitPrinting("ORDER-B", chunkIndex = 1, totalChunks = null)
        manager.emitPrinting("ORDER-A", chunkIndex = 2, totalChunks = null)
        manager.notifyFailed("ORDER-B", "Paper out")
        manager.notifySuccess("ORDER-A")
        testScheduler.advanceUntilIdle()

        val forA = events.filter { it.externalJobId == "ORDER-A" }
        val forB = events.filter { it.externalJobId == "ORDER-B" }

        assertEquals(
            "ORDER-A lifecycle: Enqueued → 2 Printing → Completed",
            listOf(
                JobEvent.Enqueued("ORDER-A"),
                JobEvent.Printing("ORDER-A", 1, null),
                JobEvent.Printing("ORDER-A", 2, null),
                JobEvent.Completed("ORDER-A")
            ),
            forA
        )
        assertEquals(
            "ORDER-B lifecycle: Enqueued → 1 Printing → Failed",
            listOf(
                JobEvent.Enqueued("ORDER-B"),
                JobEvent.Printing("ORDER-B", 1, null),
                JobEvent.Failed("ORDER-B", "Paper out")
            ),
            forB
        )
    }

    // --- Helpers --------------------------------------------------------------

    /**
     * Subscribes to [PrinterCallbackManager.jobEvents] on [scope] and returns
     * the accumulating list. Emissions made BEFORE this is called are NOT
     * captured — the flow has `replay = 0` by contract.
     */
    private fun collectEvents(scope: TestScope): MutableList<JobEvent> {
        val out = mutableListOf<JobEvent>()
        manager.jobEvents
            .onEach { out += it }
            .launchIn(scope.backgroundScope)
        // Each flow-based test runs under UnconfinedTestDispatcher, so the
        // collector subscribes eagerly here — no explicit advance needed.
        return out
    }

}
