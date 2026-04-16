package com.yotech.valtprinter.data.source

import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import java.lang.reflect.Method

fun checkMethods() {
    val methods: Array<Method> = CloudPrinter::class.java.methods
    methods.forEach {
        val n = it.name.lowercase()
        if (n.contains("print") || n.contains("image") || n.contains("bitmap") || n.contains("pic")) {
            println("METHOD: ${it.name}(" + it.parameterTypes.joinToString { p -> p.name } + ")")
        }
    }
}
