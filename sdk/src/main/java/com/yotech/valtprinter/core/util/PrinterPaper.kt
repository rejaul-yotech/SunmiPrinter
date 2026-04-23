package com.yotech.valtprinter.core.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pixel width of one row of receipt content, expressed in physical printer dots.
 *
 * The Sunmi 80mm thermal printers ValtPrinter targets render at 8 dots/mm. After
 * the small print-head margin, that gives **576 dots** of usable width per row.
 * ESC/POS raster commands and the Sunmi SDK's bitmap path both assume bitmaps
 * arrive at exactly this width; mismatches cause the printer to truncate the
 * right edge or emit garbled lines.
 *
 * **This constant is the single source of truth.** Used by:
 *
 *  - [BitmapRenderer]: the off-screen `ComposeView` is forced to this exact
 *    width via [android.view.ViewGroup.LayoutParams] and [android.view.View.MeasureSpec],
 *    so layout produces a deterministic single bitmap that can be sliced safely.
 *  - The receipt composables (`PosPrintingScreen`, `KitchenReceipt`,
 *    `RestaurantHeader`, `RawTextScreen`): each declares
 *    `Modifier.width(`[PRINTER_PAPER_WIDTH_DP]`)` so the on-screen layout
 *    matches the off-screen capture pixel-for-pixel.
 *  - Fallback bitmap creation when the headless render returns null.
 *
 * **Never hardcode `576` anywhere else** — use this constant or its dp twin
 * [PRINTER_PAPER_WIDTH_DP]. Any drift between the two would silently corrupt
 * either the print output or the on-screen preview.
 *
 * If a future printer model needs a different width (e.g. 58mm = 384 dots),
 * change this constant and add the new model to the SDK's printer-profile
 * matrix — DO NOT introduce a competing magic number.
 */
const val PRINTER_PAPER_WIDTH_PX: Int = 576

/**
 * The dp twin of [PRINTER_PAPER_WIDTH_PX]. Use this in `Modifier.width(...)`
 * on every composable that participates in receipt rendering — both the
 * headless capture pipeline and any host-app preview screens.
 *
 * The two constants are coupled by definition: dp == px under the
 * `Density(density = 1f, fontScale = 1f)` that [BitmapRenderer] installs via
 * `CompositionLocalProvider(LocalDensity provides …)` for the off-screen
 * render. **Do not separate them** — adding a `577.dp` somewhere would
 * silently desynchronise the preview from the print output.
 */
val PRINTER_PAPER_WIDTH_DP: Dp = PRINTER_PAPER_WIDTH_PX.dp
