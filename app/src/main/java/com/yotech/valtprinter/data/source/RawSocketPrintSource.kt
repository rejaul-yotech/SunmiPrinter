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
     * [2D Smart Trim]: Calculates the bounding box (minX, maxX, minY, maxY) of non-white pixels
     * to eliminate all horizontal and vertical white space.
     */
    private fun decodeBitmapToEscPos(bmp: Bitmap): ByteArray {
        val fullWidth = bmp.width
        val fullHeight = bmp.height

        // Extract all pixels at once
        val pixels = IntArray(fullWidth * fullHeight)
        bmp.getPixels(pixels, 0, fullWidth, 0, 0, fullWidth, fullHeight)

        // 1. [2D Smart Trim] Find the bounding box of non-white pixels
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
                // If pixel is not white (alpha > 50% and luminance < 200)
                if (a > 128 && luminance < 128) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // If no content found, print a tiny 1x1 empty bit to avoid printer errors
        if (maxX == -1) {
            return byteArrayOf(0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
        }

        // 2. Calculate trimmed dimensions
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
