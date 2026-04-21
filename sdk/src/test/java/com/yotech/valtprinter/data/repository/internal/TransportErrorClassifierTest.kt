package com.yotech.valtprinter.data.repository.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TransportErrorClassifier]. This classifier is the single
 * source of truth for deciding whether a printer failure should trigger
 * recovery (transport loss) or surface to the host (hardware/protocol fault).
 *
 * A false negative here would leave the queue stuck waiting for a dead
 * transport; a false positive would chew through reconnect attempts for a
 * real hardware fault. Both are production-facing bugs — this suite pins the
 * behaviour of every documented marker plus the `unknown+commit` special case.
 */
class TransportErrorClassifierTest {

    // --- Null / blank input ---------------------------------------------------

    @Test
    fun `null reason is not a transport loss`() {
        assertFalse(TransportErrorClassifier.isTransportLoss(null))
    }

    @Test
    fun `empty reason is not a transport loss`() {
        assertFalse(TransportErrorClassifier.isTransportLoss(""))
    }

    @Test
    fun `blank reason is not a transport loss`() {
        assertFalse(TransportErrorClassifier.isTransportLoss("   "))
    }

    // --- Documented transport-loss markers ------------------------------------

    @Test
    fun `not connected marker classifies as transport loss`() {
        assertTrue(TransportErrorClassifier.isTransportLoss("Printer not connected"))
    }

    @Test
    fun `disconnect marker classifies as transport loss`() {
        assertTrue(TransportErrorClassifier.isTransportLoss("Unexpected disconnect from BT"))
    }

    @Test
    fun `socket marker classifies as transport loss`() {
        assertTrue(TransportErrorClassifier.isTransportLoss("socket closed"))
    }

    @Test
    fun `timeout marker classifies as transport loss`() {
        assertTrue(
            TransportErrorClassifier.isTransportLoss(
                "CommitCut timeout after 30000ms — transport likely dead"
            )
        )
    }

    @Test
    fun `offline marker classifies as transport loss`() {
        assertTrue(TransportErrorClassifier.isTransportLoss("Printer reported offline"))
    }

    @Test
    fun `commitcut error marker classifies as transport loss`() {
        assertTrue(TransportErrorClassifier.isTransportLoss("CommitCut Error: IO_FAILURE"))
    }

    @Test
    fun `printer null marker classifies as transport loss`() {
        // PrintPipeline.finalCut emits "Printer null on finalCut" when the
        // atomic connection snapshot clears mid-job. Queue dispatcher must
        // treat this as transport loss and auto-pause, not as a hard failure.
        assertTrue(TransportErrorClassifier.isTransportLoss("Printer null on finalCut"))
    }

    // --- Case-insensitivity ---------------------------------------------------

    @Test
    fun `markers match case insensitively`() {
        assertTrue(TransportErrorClassifier.isTransportLoss("SOCKET CLOSED"))
        assertTrue(TransportErrorClassifier.isTransportLoss("DisCoNnEcTed"))
        assertTrue(TransportErrorClassifier.isTransportLoss("Timeout"))
    }

    // --- Special case: Sunmi SDK "Commit ... UNKNOWN" -------------------------

    @Test
    fun `commit plus unknown is classified as transport loss`() {
        // Sunmi SDK emits "Commit ... UNKNOWN" on session tear-down; neither
        // word alone is definitive but the combination is a transport signal.
        assertTrue(TransportErrorClassifier.isTransportLoss("CommitCut Error: UNKNOWN"))
    }

    @Test
    fun `unknown alone is NOT a transport loss`() {
        // "unknown" without "commit" is a hardware-fault catchall — must not
        // trigger recovery. Prevents regressions where we'd spin reconnecting
        // on a legitimately unknown printer fault.
        assertFalse(TransportErrorClassifier.isTransportLoss("Unknown printer error"))
    }

    @Test
    fun `commit alone is NOT a transport loss`() {
        // "commit" without "unknown" and without "commitcut error" should fall
        // through as a non-transport fault — guards against over-broad matching.
        assertFalse(TransportErrorClassifier.isTransportLoss("commit succeeded"))
    }

    // --- Negative cases: hardware / protocol faults ---------------------------

    @Test
    fun `paper out is NOT a transport loss`() {
        assertFalse(TransportErrorClassifier.isTransportLoss("Paper out"))
    }

    @Test
    fun `cover open is NOT a transport loss`() {
        assertFalse(TransportErrorClassifier.isTransportLoss("Cover open"))
    }

    @Test
    fun `overheat is NOT a transport loss`() {
        assertFalse(TransportErrorClassifier.isTransportLoss("Printer head overheat"))
    }

    @Test
    fun `generic malformed command is NOT a transport loss`() {
        assertFalse(TransportErrorClassifier.isTransportLoss("Malformed ESC POS command"))
    }
}
