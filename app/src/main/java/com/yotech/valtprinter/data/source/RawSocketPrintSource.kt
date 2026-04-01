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
     */
    private fun decodeBitmapToEscPos(bmp: Bitmap): ByteArray {
        val width = bmp.width
        val height = bmp.height

        // Calculate bytes per line. Width is in pixels, 8 pixels per byte.
        val xL = ((width + 7) / 8) % 256
        val xH = ((width + 7) / 8) / 256
        
        val yL = height % 256
        val yH = height / 256

        val command = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            xL.toByte(), xH.toByte(), yL.toByte(), yH.toByte()
        )

        // The image data buffer
        val data = ByteArray((width + 7) / 8 * height)
        var index = 0

        for (y in 0 until height) {
            for (x in 0 until width step 8) {
                var b = 0
                for (k in 0..7) {
                    var isBlack = false
                    if (x + k < width) {
                        val pixel = bmp.getPixel(x + k, y)
                        // A simple threshold for black and white conversion
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val bColor = Color.blue(pixel)
                        val a = Color.alpha(pixel)

                        // Convert to grayscale
                        val luminance = (r * 0.299 + g * 0.587 + bColor * 0.114).toInt()
                        
                        // If pixel is mostly non-transparent and dark, make it black (1 for ESC/POS)
                        if (a > 128 && luminance < 128) {
                            isBlack = true
                        }
                    }
                    if (isBlack) {
                        b = b or (1 shl (7 - k))
                    }
                }
                data[index++] = b.toByte()
            }
        }

        val result = ByteArray(command.size + data.size)
        System.arraycopy(command, 0, result, 0, command.size)
        System.arraycopy(data, 0, result, command.size, data.size)

        return result
    }
}
