package com.yotech.valtprinter.domain.util

import com.google.gson.Gson
import com.yotech.valtprinter.domain.model.PrintPayload
import com.yotech.valtprinter.domain.model.orderdata.BillingData

/**
 * Bidirectional bridge between the persisted JSON column and the typed [PrintPayload] sealed
 * class. [parse] is the read path used by [QueueDispatcher] to materialise a job back into a
 * renderable model; [serialize] is the write path used by `ValtPrinterSdk.submitPrintJob` so
 * that callers never need to know the on-disk format.
 */

class PayloadParser constructor(
    private val gson: Gson
) {
    fun parse(jsonString: String): PrintPayload {
        return try {
            // Basic heuristic: if it looks like a simple text string and not highly structured JSON, treat as RawText.
            // If it has "items" or "restaurantName", treat as BillingData.

            // Note: In an "Elite" production system, this could check for an explicit "payloadType" field.
            if (jsonString.contains("\"restaurantName\"") || jsonString.contains("\"items\"")) {
                val billingData = gson.fromJson(jsonString, BillingData::class.java)
                PrintPayload.Billing(billingData)
            } else if (!jsonString.trim().startsWith("{") && !jsonString.trim().startsWith("[")) {
                // Not standard JSON, treat as raw text
                PrintPayload.RawText(jsonString)
            } else {
                // It's JSON but we don't recognize the schema currently. Fallback.
                PrintPayload.Unknown(jsonString)
            }
        } catch (e: Exception) {
            // Parsing entirely failed, fallback to treating the exact string as raw text.
            PrintPayload.RawText(jsonString)
        }
    }

    /**
     * Serialize a [PrintPayload] into the on-disk JSON form that [parse] can round-trip.
     * The output is what gets stored in [PrintJobEntity.payloadJson].
     */
    fun serialize(payload: PrintPayload): String = when (payload) {
        is PrintPayload.Billing -> gson.toJson(payload.data)
        is PrintPayload.KitchenReceipt -> gson.toJson(payload.data)
        is PrintPayload.RawText -> payload.text
        is PrintPayload.Unknown -> payload.rawJson
    }
}
