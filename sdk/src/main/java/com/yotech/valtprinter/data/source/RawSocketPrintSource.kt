package com.yotech.valtprinter.data.source

import android.graphics.Bitmap
import android.util.Log
import com.yotech.valtprinter.domain.model.PrintResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Raw ESC/POS socket transport for LAN-attached Sunmi printers.
 *
 * ## Atomicity model
 *
 * A LAN print job is a *single* TCP session that spans the entire receipt:
 *
 * ```
 *   openJob()       ──► writes ESC/POS preamble (alignment, margins, line spacing,
 *                       backfeed, printable width). NO image, NO cut.
 *
 *   appendChunk()   ──► repeatedly writes raster image bytes (GS v 0).
 *                       May be called many times. NO cut.
 *
 *   commitAndCut()  ──► writes feed (ESC J 96) + partial cut (GS V 1),
 *                       flushes, closes the socket. EXACTLY ONE cut per job.
 * ```
 *
 * This mirrors the USB/BT transaction model in [SdkPrintSource]:
 * `initBuffer → addToBuffer × N → commitAndCut`.
 *
 * ## Why this replaces the old per-call cut design
 *
 * The previous implementation appended `ESC J 96` + `GS V 1` at the end of every
 * `printBitmap()` call. When the [com.yotech.valtprinter.data.queue.QueueDispatcher]
 * loop drove that source once per 400-px slice, the printer cut paper between
 * slices, producing a strip of mini-receipts instead of one continuous receipt.
 * Splitting the cut from the chunk write fixes that bug without sacrificing the
 * per-chunk DB checkpointing the queue relies on for resilience.
 *
 * ## Failure semantics
 *
 * If any socket write fails, the [Session] is closed and a [PrintResult.Failure]
 * is returned. A LAN job CANNOT be resumed mid-stream — the printer has already
 * advanced through whatever bytes arrived before the drop. Callers must treat any
 * LAN failure inside a job as "full reprint required" (chunk index reset to 0)
 * on the next attempt.
 */
class RawSocketPrintSource {

    /**
     * A live LAN print job. Owns one TCP socket, one [OutputStream], and a one-shot
     * `closed` flag. Created by [openJob], terminated by [commitAndCut] (success
     * path) or by [appendChunk] / [commitAndCut] on any I/O error.
     */
    class Session internal constructor(
        internal val socket: Socket,
        internal val out: OutputStream
    ) {
        @Volatile internal var closed: Boolean = false

        internal fun closeQuietly() {
            if (closed) return
            closed = true
            try { out.flush() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Opens a TCP session and writes the pre-image ESC/POS preamble.
     * Returns the live [Session] on success, or [OpenResult.Failure] on any
     * connect/write error (the partial socket is closed before returning).
     *
     * `port <= 0` is normalised to the standard ESC/POS port `9100`.
     */
    suspend fun openJob(ip: String, port: Int): OpenResult = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket().apply {
                connect(InetSocketAddress(ip, port.takeIf { it > 0 } ?: 9100), 4000)
            }
            val out = socket.getOutputStream()

            // ───── Pre-image ESC/POS preamble ─────────────────────────────
            // Intentionally skipping ESC @ (Initialize) to avoid the firmware-default
            // top feed — every relevant register is set manually below.

            // Alignment LEFT — aligns the 576-dot bitmap 1:1 with the head
            out.write(byteArrayOf(0x1B, 0x61, 0x00))

            // Enable auto-backfeed: GS ( K pL pH m n
            out.write(byteArrayOf(0x1D, 0x28, 0x4B, 0x02, 0x00, 0x02, 0x02))

            // Manual backfeed 96 dots (12 mm): ESC K n — pulls paper back so the
            // first pixel prints flush with the previous cut edge.
            out.write(byteArrayOf(0x1B, 0x4B, 0x60))

            // Line spacing = 0 (avoid blank dots between successive raster blocks)
            out.write(byteArrayOf(0x1B, 0x33, 0x00))

            // Left margin = 0
            out.write(byteArrayOf(0x1D, 0x4C, 0x00, 0x00))

            // Printable area width = 576 dots (standard 80 mm head)
            out.write(byteArrayOf(0x1D, 0x57, 0x40, 0x02))

            // Top margin = 0 (GS L 0 0 — restated for the top register)
            out.write(byteArrayOf(0x1D, 0x4C, 0x00, 0x00))

            out.flush()
            OpenResult.Ok(Session(socket, out))
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            val msg = "Socket Open Error: ${e.message}"
            Log.e("RAW_SOCKET", msg, e)
            OpenResult.Failure(msg)
        }
    }

    /**
     * Streams one bitmap chunk into the open [session] as a `GS v 0` raster block.
     * Does NOT cut. Closes the session on I/O error.
     */
    suspend fun appendChunk(session: Session, bitmap: Bitmap): PrintResult =
        withContext(Dispatchers.IO) {
            if (session.closed) return@withContext PrintResult.Failure("LAN session already closed")
            try {
                val imageData = decodeBitmapToEscPos(bitmap)
                session.out.write(imageData)
                session.out.flush()
                PrintResult.Success
            } catch (e: Exception) {
                session.closeQuietly()
                val msg = "Socket Chunk Error: ${e.message}"
                Log.e("RAW_SOCKET", msg, e)
                PrintResult.Failure(msg)
            }
        }

    /**
     * Writes the final feed + partial cut, flushes, and closes the [session].
     * After this call the session is unusable. Idempotent — calling on an
     * already-closed session returns [PrintResult.Failure] without side effects.
     */
    suspend fun commitAndCut(session: Session): PrintResult = withContext(Dispatchers.IO) {
        if (session.closed) {
            return@withContext PrintResult.Failure("LAN session closed before cut")
        }
        try {
            // ESC J 96 — feed 96 dots (12 mm) past the printed area so the cutter
            // blade clears the last printed line.
            session.out.write(byteArrayOf(0x1B, 0x4A, 0x60))
            // GS V 1 — partial cut without additional feeding.
            session.out.write(byteArrayOf(0x1D, 0x56, 0x01))
            session.out.flush()
            PrintResult.Success
        } catch (e: Exception) {
            val msg = "Socket Cut Error: ${e.message}"
            Log.e("RAW_SOCKET", msg, e)
            PrintResult.Failure(msg)
        } finally {
            session.closeQuietly()
        }
    }

    /**
     * Decodes a Bitmap into the standard ESC/POS GS v 0 raster image format.
     * No trimming — the Compose padding is reproduced 1:1 on paper.
     */
    private fun decodeBitmapToEscPos(bmp: Bitmap): ByteArray {
        val fullWidth = bmp.width
        val fullHeight = bmp.height

        val pixels = IntArray(fullWidth * fullHeight)
        bmp.getPixels(pixels, 0, fullWidth, 0, 0, fullWidth, fullHeight)

        val minX = 0
        val maxX = fullWidth - 1
        val minY = 0
        val maxY = fullHeight - 1

        val trimmedWidth = maxX - minX + 1
        val trimmedHeight = maxY - minY + 1

        val bytesPerLine = (trimmedWidth + 7) / 8
        val xL = (bytesPerLine % 256).toByte()
        val xH = (bytesPerLine / 256).toByte()
        val yL = (trimmedHeight % 256).toByte()
        val yH = (trimmedHeight / 256).toByte()

        val command = byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH)
        val data = ByteArray(bytesPerLine * trimmedHeight)

        for (y in 0 until trimmedHeight) {
            val yOffset = (y + minY) * fullWidth
            for (x in 0 until trimmedWidth step 8) {
                var b = 0
                for (k in 0..7) {
                    if (x + k < trimmedWidth) {
                        val pixel = pixels[yOffset + (x + k + minX)]
                        val a = (pixel shr 24) and 0xff
                        val r = (pixel shr 16) and 0xff
                        val g = (pixel shr 8) and 0xff
                        val bColor = pixel and 0xff
                        val luminance = (r * 0.299 + g * 0.587 + bColor * 0.114).toInt()
                        if (a > 10 && luminance < 160) {
                            b = b or (1 shl (7 - k))
                        }
                    }
                }
                data[y * bytesPerLine + (x / 8)] = b.toByte()
            }
        }

        val result = ByteArray(command.size + data.size)
        System.arraycopy(command, 0, result, 0, command.size)
        System.arraycopy(data, 0, result, command.size, data.size)
        return result
    }

    /** Result type for [openJob]. Discriminated so the caller cannot ignore the failure case. */
    sealed class OpenResult {
        data class Ok(val session: Session) : OpenResult()
        data class Failure(val reason: String) : OpenResult()
    }
}
