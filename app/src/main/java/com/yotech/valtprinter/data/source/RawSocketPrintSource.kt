package com.yotech.valtprinter.data.source

import android.graphics.Bitmap
import android.util.Log
import com.yotech.valtprinter.domain.model.PrintResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RawSocketPrintSource @Inject constructor() {

    suspend fun printBitmap(ip: String, port: Int, bitmap: Bitmap): PrintResult =
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(ip, port.takeIf { it > 0 } ?: 9100), 4000)
                val out: OutputStream = socket.getOutputStream()

                // 1. Skip ESC @ (Initialize) to avoid firmware-default paper feed
                // We manually set all parameters below instead of a full reset.

                // 2. Set Alignment Center
                out.write(byteArrayOf(0x1B, 0x61, 0x01))

                // 2.0.1 [Absolute Edge] Enable Auto-Backfeed via GS ( K
                // GS ( K <pL> <pH> <m> <n> -> 0x1D 0x28 0x4B 0x02 0x00 0x02 0x02
                out.write(byteArrayOf(0x1D, 0x28, 0x4B, 0x02, 0x00, 0x02, 0x02))

                // 2.0.2 [Absolute Edge] Manual Backfeed attempt (Uppercase K)
                // ESC K n -> 96 dots = 12mm.
                out.write(byteArrayOf(0x1B, 0x4B, 0x60)) // 0x60 = 96

                // 2.1 Set Line Spacing to 0 (to avoid vertical gaps between graphics/text)
                out.write(byteArrayOf(0x1B, 0x33, 0x00))

                // 2.2 Set Left Margin to 0
                out.write(byteArrayOf(0x1D, 0x4C, 0x00, 0x00))

                // 2.3 Set Printable Area Width to 576 dots (standard 80mm)
                // 576 = 0x40 (64) + 0x02 (2) * 256
                out.write(byteArrayOf(0x1D, 0x57, 0x40, 0x02))

                // 2.4 [Absolute Edge] Set Top Margin to 0 explicitly
                // GS L nL nH -> Set left/top relative position to 0
                out.write(byteArrayOf(0x1D, 0x4C, 0x00, 0x00))

                // 3. Print Image using GS v 0
                val imageData = decodeBitmapToEscPos(bitmap)
                out.write(imageData)

                // 4. Cut Paper (Safe Tight Mode)
                // ESC J n -> Feed n dots forward. 96 dots = 12mm.
                // Restored to 96 dots for safer cutting and top/bottom symmetry.
                out.write(byteArrayOf(0x1B, 0x4A, 0x60)) // 96 dots = 0x60

                // GS V 1 -> Partial Cut without additional feeding
                out.write(byteArrayOf(0x1D, 0x56, 0x01))

                out.flush()
                PrintResult.Success
            } catch (e: Exception) {
                val errorMsg = "Socket Socket Error: ${e.message}"
                Log.e("RAW_SOCKET", errorMsg, e)
                PrintResult.Failure(errorMsg)
            } finally {
                socket?.close()
            }
        }

    /**
     * Decodes a Bitmap into the standard ESC/POS GS v 0 raster image format.
     * [Tight 2D Trim]: Calculates the absolute bounding box (minX, maxX, minY, maxY) 
     * with zero buffer for absolute minimum white space.
     */
    private fun decodeBitmapToEscPos(bmp: Bitmap): ByteArray {
        val fullWidth = bmp.width
        val fullHeight = bmp.height

        val pixels = IntArray(fullWidth * fullHeight)
        bmp.getPixels(pixels, 0, fullWidth, 0, 0, fullWidth, fullHeight)

        // 1. [Tight 2D Trim] Find the absolute bounding box of non-white pixels
        var minX = fullWidth
        var maxX = -1
        var minY = fullHeight
        var maxY = -1

        for (y in 0 until fullHeight) {
            val yOffset = y * fullWidth
            for (x in 0 until fullWidth) {
                val pixel = pixels[yOffset + x]
                val a = (pixel shr 24) and 0xff
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val bColor = pixel and 0xff
                val luminance = (r * 0.299 + g * 0.587 + bColor * 0.114).toInt()

                // Keep threshold at 160 for original text quality
                if (a > 10 && luminance < 160) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX == -1) {
            return byteArrayOf(0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
        }

        // 2. Absolute Tight Dimensions
        val trimmedWidth = maxX - minX + 1
        val trimmedHeight = maxY - minY + 1

        val bytesPerLine = (trimmedWidth + 7) / 8
        val xL = (bytesPerLine % 256).toByte()
        val xH = (bytesPerLine / 256).toByte()
        val yL = (trimmedHeight % 256).toByte()
        val yH = (trimmedHeight / 256).toByte()

        val command = byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH)
        val data = ByteArray(bytesPerLine * trimmedHeight)

        // 3. Rasterize only the detected bounding box
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
}
