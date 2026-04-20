package com.yotech.valtprinter.domain.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yotech.valtprinter.domain.model.PrintPayload
import com.yotech.valtprinter.domain.model.ReceiptData
import com.yotech.valtprinter.domain.model.orderdata.BillingData

/**
 * Parses the JSON payload submitted via [com.yotech.valtprinter.sdk.ValtPrinterSdk.submitPrintJob]
 * into a typed [PrintPayload] for the [com.yotech.valtprinter.data.queue.QueueDispatcher].
 *
 * ## Canonical wire format
 *
 * ```json
 *   { "type": "BILLING",          "data": { ...BillingData... } }
 *   { "type": "KITCHEN_RECEIPT",  "data": { ...ReceiptData... } }
 *   { "type": "RAW_TEXT",         "text": "..." }
 * ```
 *
 * The discriminator is a top-level `"type"` field. The dispatcher uses it
 * verbatim to choose which receipt template to render.
 *
 * ## Why an explicit discriminator
 *
 * The previous implementation guessed payload type by scanning for substrings
 * like `"restaurantName"` or `"items"`. Two ways that broke:
 *
 * 1. A `BillingData` whose `restaurantName` was empty/missing fell through to
 *    [PrintPayload.Unknown], which the dispatcher then rendered as
 *    `RawTextScreen(rawJson)` — a wall of curly braces and quotes printed on
 *    paper.
 * 2. Any custom payload that happened to contain the word `"items"` was
 *    misclassified as billing and crashed the Gson `fromJson` call.
 *
 * The typed discriminator removes both classes of failure. A payload missing
 * `"type"` falls back to the legacy heuristic for backward compatibility, with
 * a one-line warning so the host can be migrated.
 */
class PayloadParser(
    private val gson: Gson
) {
    fun parse(jsonString: String): PrintPayload {
        val trimmed = jsonString.trim()

        // 1. Plain text — never starts with { or [
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return PrintPayload.RawText(trimmed)
        }

        // 2. Try the canonical typed envelope.
        val envelope: JsonObject? = try {
            JsonParser.parseString(trimmed) as? JsonObject
        } catch (e: Exception) {
            Log.w("PAYLOAD_PARSER", "JSON parse failed; treating as raw text. err=${e.message}")
            return PrintPayload.RawText(trimmed)
        }

        if (envelope != null && envelope.has("type")) {
            return parseTypedEnvelope(envelope, trimmed)
        }

        // 3. Legacy heuristic — kept until all hosts emit the typed envelope.
        Log.w(
            "PAYLOAD_PARSER",
            "Untyped JSON payload — falling back to heuristic. " +
                "Migrate the host to {\"type\":\"BILLING\",\"data\":{...}} format."
        )
        return parseLegacyHeuristic(envelope, trimmed)
    }

    /**
     * Serializes a typed [PrintPayload] back into the canonical wire format so
     * producers (e.g. `ValtPrinterSdk.submitPrintJob`) and the parser stay
     * symmetric.
     */
    fun serialize(payload: PrintPayload): String = when (payload) {
        is PrintPayload.Billing -> gson.toJson(
            mapOf("type" to TYPE_BILLING, "data" to payload.data)
        )
        is PrintPayload.KitchenReceipt -> gson.toJson(
            mapOf("type" to TYPE_KITCHEN_RECEIPT, "data" to payload.data)
        )
        is PrintPayload.RawText -> gson.toJson(
            mapOf("type" to TYPE_RAW_TEXT, "text" to payload.text)
        )
        is PrintPayload.Unknown -> payload.rawJson
    }

    private fun parseTypedEnvelope(envelope: JsonObject, original: String): PrintPayload {
        val type = envelope.get("type").asString.uppercase()
        return when (type) {
            TYPE_BILLING -> {
                val dataElement = envelope.get("data")
                if (dataElement == null || !dataElement.isJsonObject) {
                    Log.w("PAYLOAD_PARSER", "BILLING envelope missing 'data' object.")
                    return PrintPayload.Unknown(original)
                }
                try {
                    PrintPayload.Billing(gson.fromJson(dataElement, BillingData::class.java))
                } catch (e: Exception) {
                    Log.e("PAYLOAD_PARSER", "BillingData deserialisation failed: ${e.message}")
                    PrintPayload.Unknown(original)
                }
            }

            TYPE_KITCHEN_RECEIPT -> {
                val dataElement = envelope.get("data")
                if (dataElement == null || !dataElement.isJsonObject) {
                    Log.w("PAYLOAD_PARSER", "KITCHEN_RECEIPT envelope missing 'data' object.")
                    return PrintPayload.Unknown(original)
                }
                try {
                    PrintPayload.KitchenReceipt(gson.fromJson(dataElement, ReceiptData::class.java))
                } catch (e: Exception) {
                    Log.e("PAYLOAD_PARSER", "ReceiptData deserialisation failed: ${e.message}")
                    PrintPayload.Unknown(original)
                }
            }

            TYPE_RAW_TEXT -> {
                val textElement = envelope.get("text")
                if (textElement == null || !textElement.isJsonPrimitive) {
                    Log.w("PAYLOAD_PARSER", "RAW_TEXT envelope missing 'text' string.")
                    return PrintPayload.Unknown(original)
                }
                PrintPayload.RawText(textElement.asString)
            }

            else -> {
                Log.w("PAYLOAD_PARSER", "Unknown envelope type='$type'.")
                PrintPayload.Unknown(original)
            }
        }
    }

    private fun parseLegacyHeuristic(envelope: JsonObject?, original: String): PrintPayload {
        if (envelope == null) return PrintPayload.Unknown(original)
        val looksLikeBilling = envelope.has("restaurantName") || envelope.has("items")
        return if (looksLikeBilling) {
            try {
                PrintPayload.Billing(gson.fromJson(original, BillingData::class.java))
            } catch (e: Exception) {
                Log.e("PAYLOAD_PARSER", "Legacy heuristic billing parse failed: ${e.message}")
                PrintPayload.Unknown(original)
            }
        } else {
            PrintPayload.Unknown(original)
        }
    }

    private companion object {
        const val TYPE_BILLING = "BILLING"
        const val TYPE_KITCHEN_RECEIPT = "KITCHEN_RECEIPT"
        const val TYPE_RAW_TEXT = "RAW_TEXT"
    }
}
