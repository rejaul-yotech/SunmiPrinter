package com.yotech.valtprinter.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the printer-paper width contract.
 *
 * The `576` magic number used to live, hardcoded, in 6 sites across
 * `BitmapRenderer.kt` and the receipt composables. Any drift between those
 * sites silently corrupts either the printed output or the on-screen preview.
 * Improvement #3 from the audit consolidated all six into a single
 * [PRINTER_PAPER_WIDTH_PX] / [PRINTER_PAPER_WIDTH_DP] pair. This test prevents
 * a future PR from re-introducing a competing literal.
 *
 * Two enforcement paths:
 *   1. Pin the constants themselves — changing the value requires updating
 *      this test, which forces a deliberate decision.
 *   2. Static scan: read each receipt-rendering source file and assert it
 *      contains no `576` literal. (`PrinterPaper.kt` itself is the only
 *      file allowed to mention the number directly.)
 */
class PrinterPaperContractTest {

    /**
     * Receipt-rendering files that MUST NOT contain a hardcoded `576`. If you
     * add a new composable that participates in receipt rendering, add it
     * here too — and use [PRINTER_PAPER_WIDTH_DP] for any width modifier.
     */
    private val GUARDED_SOURCES = listOf(
        "src/main/java/com/yotech/valtprinter/core/util/BitmapRenderer.kt",
        "src/main/java/com/yotech/valtprinter/ui/receipt/PosPrintingScreen.kt",
        "src/main/java/com/yotech/valtprinter/ui/receipt/KitchenReceipt.kt",
        "src/main/java/com/yotech/valtprinter/ui/receipt/RestaurantHeader.kt",
        "src/main/java/com/yotech/valtprinter/ui/receipt/RawTextScreen.kt",
    )

    @Test
    fun `PRINTER_PAPER_WIDTH_PX is pinned to the value the Sunmi 80mm head expects`() {
        // 80mm × 8 dots/mm − margin = 576. If you change this, you are also
        // committing to verify every receipt template re-flows correctly at
        // the new width AND to add a printer-profile mechanism.
        assertEquals(576, PRINTER_PAPER_WIDTH_PX)
    }

    @Test
    fun `PRINTER_PAPER_WIDTH_DP mirrors the px constant exactly`() {
        // BitmapRenderer installs Density(1f, 1f) for the off-screen render,
        // so 1 dp == 1 px there — the two constants MUST match numerically.
        assertEquals(
            PRINTER_PAPER_WIDTH_PX.toFloat(),
            PRINTER_PAPER_WIDTH_DP.value,
            /* delta = */ 0.0f
        )
    }

    @Test
    fun `no receipt-rendering source file contains a hardcoded 576 literal`() {
        val moduleRoot = locateModuleRoot()
        val offenders = mutableListOf<String>()

        for (relPath in GUARDED_SOURCES) {
            val file = File(moduleRoot, relPath)
            assertTrue(
                "Guarded source not found: $file. If the file moved, update " +
                    "GUARDED_SOURCES in this test.",
                file.exists()
            )
            file.readLines().forEachIndexed { index, line ->
                // Ignore comments — KDoc and explanatory comments may quote
                // `576` for clarity. We only care about real code mentions.
                val trimmed = line.trimStart()
                val isKDocLine = trimmed.startsWith("*") || trimmed.startsWith("/*")
                if (isKDocLine) return@forEachIndexed
                val codeOnly = line.substringBefore("//")
                if (BARE_576_REGEX.containsMatchIn(codeOnly)) {
                    offenders += "$relPath:${index + 1}: $line"
                }
            }
        }

        assertTrue(
            "Receipt source files contain hardcoded `576` literals — use " +
                "PRINTER_PAPER_WIDTH_PX / PRINTER_PAPER_WIDTH_DP instead. " +
                "Offenders:\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    /**
     * `576` as a standalone number — i.e. NOT part of a larger digit run like
     * `576000` or an identifier like `_576`. Allows the digits to appear
     * inside a string literal (we only want to catch numeric literals in code
     * — the constants file is the legitimate home, and is excluded from
     * GUARDED_SOURCES anyway).
     */
    private val BARE_576_REGEX = Regex("(?<![\\w\\d])576(?![\\w\\d])")

    private fun locateModuleRoot(): File {
        // Gradle runs `:sdk:testDebugUnitTest` with CWD = sdk/.
        // Fall back to `../sdk/` for runners that change CWD (rare).
        val candidates = listOf(
            File("."),
            File("sdk"),
            File("../sdk"),
        )
        return candidates.firstOrNull { File(it, "build.gradle.kts").exists() }
            ?: error(
                "Could not locate sdk/ module root. Tried: " +
                    candidates.joinToString { it.absolutePath }
            )
    }
}
