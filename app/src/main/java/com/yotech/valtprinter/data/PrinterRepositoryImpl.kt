package com.yotech.valtprinter.data

import android.content.Context
import com.sunmi.externalprinterlibrary2.ConnectCallback
import com.sunmi.externalprinterlibrary2.ResultCallback
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.CloudPrinterStatus
import com.yotech.valtprinter.domain.repository.PrinterRepository

class PrinterRepositoryImpl(private val context: Context) : PrinterRepository {
    private var cloudPrinter: CloudPrinter? = null

    override fun connect(address: String, onStatus: (String) -> Unit) {
        // In a real app, use the searchCloudPrinter method first to get the object
        // Here we assume 'cloudPrinter' is obtained via the SearchCallback
        cloudPrinter?.connect(context, object : ConnectCallback {
            override fun onConnect() = onStatus("Connected")
            override fun onFailed(error: String) = onStatus("Error: $error")
            override fun onDisConnect() = onStatus("Disconnected")
        })
    }

    override fun printLabel(content: String, onComplete: (Boolean) -> Unit) {
        cloudPrinter?.let { printer ->
            printer.setPrintDensity(100) // 80mm Label specific
            printer.printText(content)
            printer.commitTransBuffer(object : ResultCallback {
                override fun onComplete() = onComplete(true)
                override fun onFailed(p0: CloudPrinterStatus?) {
                    onComplete(false)
                }
            })
        }
    }

    override fun release() {
        cloudPrinter?.release(context)
    }
}