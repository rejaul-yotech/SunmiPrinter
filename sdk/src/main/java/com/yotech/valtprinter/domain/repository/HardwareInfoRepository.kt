package com.yotech.valtprinter.domain.repository

/**
 * Queries the hardware and OS for peripheral presence and permission state.
 * Split from [ConnectionRepository] to stay within the 6-method interface limit.
 */
interface HardwareInfoRepository {
    /** Returns true if any USB device is currently attached and visible to [UsbManager]. */
    fun isUsbPrinterPresent(): Boolean

    /**
     * Returns true if the Bluetooth device identified by [mac] is bonded (paired)
     * with this Android device.
     */
    fun isBtDeviceBonded(mac: String): Boolean

    /**
     * Returns true if the app holds [android.Manifest.permission.BLUETOOTH_CONNECT]
     * (required on API 31+). Always returns true on older devices.
     */
    fun hasBtConnectPermission(): Boolean
}
