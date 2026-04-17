package com.yotech.valtprinter.core.util

import android.util.Log

/**
 * Internal logging façade with PII redaction helpers.
 *
 * SDK code MUST route every [Log.d]/[Log.i]/[Log.w]/[Log.e] call through this object so that
 * MAC addresses, IP addresses, and stable device identifiers never reach logcat verbatim.
 * Redaction preserves enough surface (vendor OUI, /16 subnet, transport prefix) for triage
 * while stripping the unique tail that links a log line to a specific physical device or LAN host.
 */
internal object SdkLogger {

    fun d(tag: String, message: String) { Log.d(tag, message) }
    fun i(tag: String, message: String) { Log.i(tag, message) }
    fun w(tag: String, message: String) { Log.w(tag, message) }
    fun w(tag: String, message: String, t: Throwable) { Log.w(tag, message, t) }
    fun e(tag: String, message: String) { Log.e(tag, message) }
    fun e(tag: String, message: String, t: Throwable) { Log.e(tag, message, t) }

    /**
     * Redact a Bluetooth MAC address. Keeps the OUI (first 3 octets) for vendor identification,
     * masks the device-unique suffix.
     *
     * Example: `AA:BB:CC:11:22:33` → `AA:BB:CC:**:**:**`
     */
    fun redactMac(mac: String?): String {
        if (mac.isNullOrBlank()) return "<null>"
        val parts = mac.split(":", "-")
        return if (parts.size == 6) parts.take(3).joinToString(":") + ":**:**:**" else "<masked>"
    }

    /**
     * Redact an IPv4 address. Keeps the /16 subnet for routing diagnostics, masks the host part.
     *
     * Example: `192.168.1.42` → `192.168.*.*`
     */
    fun redactIp(ip: String?): String {
        if (ip.isNullOrBlank()) return "<null>"
        val parts = ip.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.*.*" else "<masked>"
    }

    /**
     * Redact a domain device id of the form `<TRANSPORT>-<unique>`.
     * Keeps the transport prefix for triage, masks the unique tail.
     *
     * Examples:
     * - `BT-AA:BB:CC:11:22:33`  → `BT-AA:BB:CC:**:**:**`
     * - `LAN-192.168.1.42`      → `LAN-192.168.*.*`
     * - `USB-1234-5678`         → `USB-****-****`
     */
    fun redactDeviceId(id: String?): String {
        if (id.isNullOrBlank()) return "<null>"
        return when {
            id.startsWith("BT-") -> "BT-" + redactMac(id.removePrefix("BT-"))
            id.startsWith("LAN-") -> "LAN-" + redactIp(id.removePrefix("LAN-"))
            id.startsWith("USB-") -> "USB-****-****"
            else -> "<masked>"
        }
    }

    /**
     * Redact a printer's display name. Vendor models are the dominant case (`SUNMI-NT211`)
     * and not PII, but custom-named devices may carry owner identifiers, so we keep the first
     * token only and mask the remainder.
     *
     * Example: `Cashier-Front-Desk-Reaj`  → `Cashier-***`
     */
    fun redactDeviceName(name: String?): String {
        if (name.isNullOrBlank()) return "<null>"
        val first = name.substringBefore('-').substringBefore(' ')
        return if (first == name) name else "$first-***"
    }
}
