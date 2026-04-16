package com.yotech.valtprinter.core.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Common date and time patterns used throughout the application.
 */
enum class DateTimeFormat(val pattern: String) {
    /** 2026-04-02 */
    DATE_DASH("yyyy-MM-dd"),

    /** 02/04/2026 */
    DATE_SLASH("dd/MM/yyyy"),

    /** 05:09 PM */
    TIME_12H("hh:mm a"),

    /** 17:09 */
    TIME_24H("HH:mm"),

    /** 02 Apr 2026, 05:09 PM */
    DATE_TIME_DISPLAY("dd MMM yyyy, hh:mm a"),

    /** 2026-04-02T17:09:45Z (Standard for Backend/APIs) */
    ISO_8601("yyyy-MM-dd'T'HH:mm:ss'Z'"),

    /** Thursday, 02 April */
    FULL_DAY_DATE("EEEE, dd MMMM")
}


/**
 * Utility object for handling date and time formatting.
 */
object DateTimeUtils {
    private const val TAG = "DateTimeUtils"

    /**
     * Retrieves the current date and time with optional TimeZone support.
     *
     * @param format The desired [DateTimeFormat] enum type.
     * @param timeZone The target TimeZone (e.g., TimeZone.getTimeZone("UTC")).
     * Defaults to the device's local TimeZone.
     * @param locale Use Locale.US for data/API, or Locale.getDefault() for UI.
     * @return Formatted string or empty string on failure.
     */
    fun getCurrentDateTime(
        format: DateTimeFormat,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.US
    ): String {
        return try {
            // 1. Get the current instance
            val calendar = Calendar.getInstance()

            // 2. Initialize the formatter
            val formatter = SimpleDateFormat(format.pattern, locale)

            // 3. Set the TimeZone (Crucial for regional/UTC accuracy)
            formatter.timeZone = timeZone

            // 4. Format the time based on the Calendar's current millis
            val result = formatter.format(calendar.time)

            Log.d(TAG, "Success: $result | Format: ${format.name} | TZ: ${timeZone.id}")
            result

        } catch (e: Exception) {
            Log.e(TAG, "Error formatting date: ${e.message}", e)
            ""
        }
    }
}