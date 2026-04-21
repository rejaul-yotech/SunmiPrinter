package com.yotech.valtprinter.domain.util

import com.google.gson.Gson
import com.yotech.valtprinter.domain.model.PrintPayload
import com.yotech.valtprinter.domain.model.orderdata.BillingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PayloadParser]. The parser is the typed boundary between
 * host-supplied JSON and the queue's [PrintPayload] sealed hierarchy — a
 * misclassification here manifests as a wall of JSON printed on paper (the
 * exact field bug the typed envelope was introduced to fix).
 *
 * Coverage in this suite:
 *   - Typed envelope: BILLING / KITCHEN_RECEIPT / RAW_TEXT
 *   - Malformed envelopes (missing `data`, wrong types, invalid inner JSON)
 *   - Plain text fallback
 *   - Legacy heuristic (untyped BillingData-like payloads)
 *   - Round-trip: serialize → parse must preserve the payload variant
 *
 * `android.util.Log` is stubbed to return default values by
 * `unitTests.isReturnDefaultValues = true` in the SDK module build script.
 */
class PayloadParserTest {

    private lateinit var gson: Gson
    private lateinit var parser: PayloadParser

    @Before
    fun setUp() {
        gson = Gson()
        parser = PayloadParser(gson)
    }

    // --- Raw text fallback (non-JSON) -----------------------------------------

    @Test
    fun `plain text returns RawText`() {
        val result = parser.parse("Hello printer")
        assertTrue(result is PrintPayload.RawText)
        assertEquals("Hello printer", (result as PrintPayload.RawText).text)
    }

    @Test
    fun `plain text is trimmed`() {
        val result = parser.parse("   line one\n")
        assertTrue(result is PrintPayload.RawText)
        assertEquals("line one", (result as PrintPayload.RawText).text)
    }

    @Test
    fun `malformed JSON falls back to RawText`() {
        // Starts with `{` so we try the envelope path; JsonParser throws; we
        // must recover with RawText of the original trimmed text.
        val malformed = "{ not valid json"
        val result = parser.parse(malformed)
        assertTrue(result is PrintPayload.RawText)
        assertEquals(malformed, (result as PrintPayload.RawText).text)
    }

    // --- Typed envelope: BILLING ----------------------------------------------

    @Test
    fun `typed BILLING envelope parses to Billing`() {
        val billing = sampleBilling()
        val json = """{"type":"BILLING","data":${gson.toJson(billing)}}"""

        val result = parser.parse(json)

        assertTrue(result is PrintPayload.Billing)
        assertEquals(billing, (result as PrintPayload.Billing).data)
    }

    @Test
    fun `typed BILLING is case-insensitive on the type field`() {
        val billing = sampleBilling()
        val json = """{"type":"billing","data":${gson.toJson(billing)}}"""

        val result = parser.parse(json)

        assertTrue(result is PrintPayload.Billing)
    }

    @Test
    fun `BILLING envelope missing data becomes Unknown`() {
        val json = """{"type":"BILLING"}"""
        val result = parser.parse(json)
        assertTrue(result is PrintPayload.Unknown)
        assertEquals(json, (result as PrintPayload.Unknown).rawJson)
    }

    @Test
    fun `BILLING envelope with non-object data becomes Unknown`() {
        val json = """{"type":"BILLING","data":"not-an-object"}"""
        assertTrue(parser.parse(json) is PrintPayload.Unknown)
    }

    // --- Typed envelope: RAW_TEXT ---------------------------------------------

    @Test
    fun `typed RAW_TEXT envelope parses to RawText`() {
        val json = """{"type":"RAW_TEXT","text":"Kitchen note"}"""
        val result = parser.parse(json)
        assertTrue(result is PrintPayload.RawText)
        assertEquals("Kitchen note", (result as PrintPayload.RawText).text)
    }

    @Test
    fun `RAW_TEXT envelope missing text becomes Unknown`() {
        val json = """{"type":"RAW_TEXT"}"""
        assertTrue(parser.parse(json) is PrintPayload.Unknown)
    }

    @Test
    fun `RAW_TEXT envelope with non-string text becomes Unknown`() {
        val json = """{"type":"RAW_TEXT","text":{"nested":"object"}}"""
        assertTrue(parser.parse(json) is PrintPayload.Unknown)
    }

    // --- Typed envelope: unknown discriminator --------------------------------

    @Test
    fun `unknown envelope type becomes Unknown`() {
        val json = """{"type":"MYSTERY","data":{}}"""
        assertTrue(parser.parse(json) is PrintPayload.Unknown)
    }

    // --- Legacy heuristic (no 'type' field) -----------------------------------

    @Test
    fun `untyped payload with restaurantName classifies as Billing via legacy heuristic`() {
        // Regression guard: the canonical envelope is preferred, but until
        // every host migrates, untyped BillingData-shaped JSON must still
        // route to Billing (not Unknown, which would print as raw JSON).
        val billing = sampleBilling()
        val json = gson.toJson(billing)
        val result = parser.parse(json)
        assertTrue(result is PrintPayload.Billing)
        assertEquals(billing, (result as PrintPayload.Billing).data)
    }

    @Test
    fun `untyped JSON object without billing markers is Unknown`() {
        val json = """{"foo":"bar","baz":123}"""
        assertTrue(parser.parse(json) is PrintPayload.Unknown)
    }

    // --- Serialize / round-trip symmetry --------------------------------------

    @Test
    fun `serialize then parse preserves Billing`() {
        val original = PrintPayload.Billing(sampleBilling())
        val roundTripped = parser.parse(parser.serialize(original))
        assertTrue(roundTripped is PrintPayload.Billing)
        assertEquals(original.data, (roundTripped as PrintPayload.Billing).data)
    }

    @Test
    fun `serialize then parse preserves RawText`() {
        val original = PrintPayload.RawText("Quick note")
        val roundTripped = parser.parse(parser.serialize(original))
        assertTrue(roundTripped is PrintPayload.RawText)
        assertEquals(original.text, (roundTripped as PrintPayload.RawText).text)
    }

    @Test
    fun `serialize of Unknown returns the original rawJson verbatim`() {
        val raw = """{"type":"MYSTERY","data":{"x":1}}"""
        val payload = PrintPayload.Unknown(raw)
        assertEquals(raw, parser.serialize(payload))
    }

    // --- Fixtures --------------------------------------------------------------

    /**
     * Minimal [BillingData] fixture. Only required (non-default) fields are
     * supplied so the test doesn't break when optional fields are added to
     * the model. Values are intentionally unremarkable — identity is what
     * the assertions compare, not the content.
     */
    private fun sampleBilling(): BillingData = BillingData(
        restaurantName = "Dishoom Kensington",
        restaurantPhone = "+44 20 7420 9320",
        addressLine1 = "4 Derry Street",
        city = "LONDON",
        postalCode = "W8 5SE",
        countryCode = "GB",
        staffName = "Test Staff",
        deviceName = "Sunmi V2 Pro",
        orderDeviceName = "Tablet-KDS-01",
        timestamp = 1_743_602_874_000L,
        orderId = "TEST-001",
        orderTag = "Table 14",
        orderReference = "CHK-TEST",
        orderType = "Dine In",
        currencyCode = "GBP",
        paymentStatus = "Paid",
        items = emptyList()
    )
}
