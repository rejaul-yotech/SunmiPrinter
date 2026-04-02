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

            // 4. Cut Paper
            // We remove manual \n feeds because GS V 66 automatically feeds to the cutter 
            out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) 

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
     * [Smart Trim]: Automatically detects the last row with content to avoid printing extra white space.
     */
    private fun decodeBitmapToEscPos(bmp: Bitmap): ByteArray {
        val width = bmp.width
        val fullHeight = bmp.height

        // Extract all pixels at once to avoid JNI overhead of getPixel()
        val pixels = IntArray(width * fullHeight)
        bmp.getPixels(pixels, 0, width, 0, 0, width, fullHeight)

        // 1. [Smart Trim] Find the last row that contains non-white pixels
        var lastContentRow = 0
        for (y in fullHeight - 1 downTo 0) {
            val yOffset = y * width
            var rowHasContent = false
            for (x in 0 until width) {
                val pixel = pixels[yOffset + x]
                
                val a = (pixel shr 24) and 0xff
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val bColor = pixel and 0xff

                val luminance = (r * 0.299 + g * 0.587 + bColor * 0.114).toInt()
                if (a > 128 && luminance < 128) {
                    rowHasContent = true
                    break
                }
            }
            if (rowHasContent) {
                lastContentRow = y
                break
            }
        }

        // Use the trimmed height (minimum 1 row)
        val trimmedHeight = if (lastContentRow > 0) lastContentRow + 1 else 1

        val bytesPerLine = (width + 7) / 8
        val xL = (bytesPerLine % 256).toByte()
        val xH = (bytesPerLine / 256).toByte()
        val yL = (trimmedHeight % 256).toByte()
        val yH = (trimmedHeight / 256).toByte()

        val command = byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH)
        val data = ByteArray(bytesPerLine * trimmedHeight)

        // 2. Rasterize only up to the trimmed height
        for (y in 0 until trimmedHeight) {
            val yOffset = y * width
            for (x in 0 until width step 8) {
                var b = 0
                for (k in 0..7) {
                    if (x + k < width) {
                        val pixel = pixels[yOffset + x + k]
                        val a = (pixel shr 24) and 0xff
                        val r = (pixel shr 16) and 0xff
                        val g = (pixel shr 8) and 0xff
                        val bColor = pixel and 0xff
                        val luminance = (r * 0.299 + g * 0.587 + bColor * 0.114).toInt()
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
