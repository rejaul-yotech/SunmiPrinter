package com.yotech.valtprinter.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class RawSocketClient {
    companion object {
        private const val PRINT_PORT = 9100 // Standard Thermal Port

        // ESC/POS Command Constants
        private val ESC_INIT = byteArrayOf(0x1B, 0x40)
        private val ESC_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
        private val GS_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)
    }

    suspend fun sendPrintJob(ip: String, title: String, user: String): Boolean = withContext(
        Dispatchers.IO
    ) {
        val socket = Socket()
        try {
            // 1. Connect directly to the IP found in your logs
            socket.connect(InetSocketAddress(ip, PRINT_PORT), 4000)
            val out = socket.getOutputStream()

            // 2. Build the Buffer
            out.write(ESC_INIT)
            out.write(ESC_CENTER)

            // Text must be encoded for the printer hardware
            out.write("\n*** $title ***\n".toByteArray(Charsets.US_ASCII))
            out.write("User: $user\n".toByteArray(Charsets.US_ASCII))

            // 3. Feed & Cut (Essential for NT311 mechanical completion)
            out.write("\n\n\n\n".toByteArray())
            out.write(GS_CUT)

            out.flush()
            true
        } catch (e: Exception) {
            false
        } finally {
            socket.close()
        }
    }
}