package com.yotech.valtprinter.domain.usecase

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

suspend fun printRawEscPos(ipAddress: String, title: String, user: String) {
    withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            // Port 9100 is the standard RAW print port for SUNMI/Epson
            socket.connect(InetSocketAddress(ipAddress, 9100), 5000)
            val outputStream: OutputStream = socket.getOutputStream()

            val escInit = byteArrayOf(0x1B, 0x40) // ESC @ (Initialize)
            val escCenter = byteArrayOf(0x1B, 0x61, 0x01) // ESC a n (Center)
            val escBoldOn = byteArrayOf(0x1B, 0x45, 0x01) // ESC E n (Bold)
            val escSizeLarge = byteArrayOf(0x1D, 0x21, 0x11) // GS ! n (Double Size)
            val escSizeNormal = byteArrayOf(0x1D, 0x21, 0x00) // Reset Size
            val lineFeed = "\n".toByteArray()

            outputStream.write(escInit)
            outputStream.write(escCenter)
            
            // Print Title
            outputStream.write(escSizeLarge)
            outputStream.write(escBoldOn)
            outputStream.write("$title\n".toByteArray(Charsets.US_ASCII))
            
            // Print User
            outputStream.write(escSizeNormal)
            outputStream.write("Printed by: $user\n".toByteArray(Charsets.US_ASCII))
            
            // Feed and Cut
            outputStream.write("\n\n\n\n".toByteArray()) 
            outputStream.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // GS V m n (Paper Cut)

            outputStream.flush()
        } catch (e: Exception) {
            Log.e("PRINTER_RAW", "Socket Error: ${e.message}")
        } finally {
            socket.close()
        }
    }
}