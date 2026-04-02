package com.yotech.valtprinter.data.source

import android.graphics.Bitmap
import android.graphics.Color
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

    suspend fun printBitmap(ip: String, port: Int, bitmap: Bitmap): PrintResult = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port.takeIf { it > 0 } ?: 9100), 4000)
            val out: OutputStream = socket.getOutputStream()

            // 1. Initialize Printer
            out.write(byteArrayOf(0x1B, 0x40))

            // 2. Set Alignment Center
            out.write(byteArrayOf(0x1B, 0x61, 0x01))

            // 3. Print Image using GS v 0
            val imageData = decodeBitmapToEscPos(bitmap)
            out.write(imageData)

            // 4. Feed & Cut
            out.write("\n\n\n\n".toByteArray())
            out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // GS V

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
     * Uses bulk pixel processing and bitwise operations for performance.
     */
    private fun decodeBitmapToEscPos(bmp: Bitmap): ByteArray {
        val width = bmp.width
        val height = bmp.height

        // Extract all pixels at once to avoid JNI overhead of getPixel()
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)

        val bytesPerLine = (width + 7) / 8
        val xL = (bytesPerLine % 256).toByte()
        val xH = (bytesPerLine / 256).toByte()
        val yL = (height % 256).toByte()
        val yH = (height / 256).toByte()

        val command = byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH)
        val data = ByteArray(bytesPerLine * height)

        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width step 8) {
                var b = 0
                for (k in 0..7) {
                    if (x + k < width) {
                        val pixel = pixels[yOffset + x + k]

                        // Extract color components faster using bitwise shifts
                        val a = (pixel shr 24) and 0xff
                        val r = (pixel shr 16) and 0xff
                        val g = (pixel shr 8) and 0xff
                        val bColor = pixel and 0xff

                        // Standard luminance conversion: 0.299R + 0.587G + 0.114B
                        val luminance = (r * 0.299 + g * 0.587 + bColor * 0.114).toInt()

                        // If pixel is mostly opaque and dark, set bit to 1 (Black)
                        if (a > 128 && luminance < 128) {
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
