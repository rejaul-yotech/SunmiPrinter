package com.yotech.valtprinter.core.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Headless renderer for Compose receipt content.
 *
 * ## Why a single full-receipt bitmap, then slice
 *
 * The previous design called [renderReceiptChunk] once per slice, which recreated
 * and recomposed a [ComposeView] for every chunk. That was wrong on three counts:
 *
 * 1. **Composition is asynchronous.** The first measure pass after `setContent` is
 *    not guaranteed to reflect a fully composed tree. Per-chunk measurement could
 *    read a `measuredHeight` of 0 (or just the placeholder) and terminate the
 *    chunk loop early.
 * 2. **Composition is non-deterministic across runs.** `remember { now() }`
 *    captures different timestamps on chunk 0 vs chunk 1. Item ordering, grouping,
 *    and any `LaunchedEffect`-derived state can diverge between chunks, producing
 *    visibly different output between adjacent slices of what should have been
 *    one continuous receipt.
 * 3. **It is wasteful.** Composing a 4 000-px receipt three times to render three
 *    400-px slices is roughly 3× the work needed.
 *
 * The new design composes the receipt **exactly once** per job into a single
 * full-height bitmap ([renderFullReceiptBitmap]), then slices that bitmap with
 * pure `Bitmap.createBitmap(...)` calls ([sliceBitmap]). Slicing is deterministic:
 * the printer prints the exact pixels that were composed, and adjacent slices
 * line up perfectly because they came from the same source.
 */
object BitmapRenderer {

    /**
     * Composes [content] once, waits for at least one full frame so Compose has a
     * chance to compose and lay out, then captures the entire receipt as a single
     * bitmap of width 576 px and `WRAP_CONTENT` height.
     *
     * Returns `null` if the composed content has zero height (empty receipt).
     */
    suspend fun renderFullReceiptBitmap(
        parentView: View,
        content: @Composable () -> Unit
    ): Bitmap? {
        val composed = composeAndLayout(parentView, content) ?: return null
        val (composeView, wrapper, width, height) = composed

        return try {
            withContext(Dispatchers.Default) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                withContext(Dispatchers.Main) {
                    composeView.draw(canvas)
                }
                bitmap
            }
        } finally {
            withContext(Dispatchers.Main) {
                (parentView.rootView as? ViewGroup)?.removeView(wrapper)
            }
        }
    }

    /**
     * Pure bitmap-region operation. Returns the [chunkIndex]-th slice of [full]
     * of height [chunkSizePx], or `null` when the slice would be past the end of
     * the bitmap. The final slice is automatically clamped to the remaining
     * height.
     */
    fun sliceBitmap(full: Bitmap, chunkIndex: Int, chunkSizePx: Int): Bitmap? {
        val startY = chunkIndex * chunkSizePx
        if (startY >= full.height) return null
        val sliceHeight = minOf(chunkSizePx, full.height - startY)
        return Bitmap.createBitmap(full, 0, startY, full.width, sliceHeight)
    }

    /**
     * Attaches the [content] to the window, waits for composition + layout to
     * settle (one pre-draw + one extra frame), then measures and lays it out to
     * a definite [PRINTER_PAPER_WIDTH_PX]-px width.
     */
    private suspend fun composeAndLayout(
        parentView: View,
        content: @Composable () -> Unit
    ): Composed? = withContext(Dispatchers.Main) {
        val view = ComposeView(parentView.context).apply {
            setViewTreeLifecycleOwner(parentView.findViewTreeLifecycleOwner())
            setViewTreeViewModelStoreOwner(parentView.findViewTreeViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(parentView.findViewTreeSavedStateRegistryOwner())
            layoutParams = ViewGroup.LayoutParams(
                PRINTER_PAPER_WIDTH_PX,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(density = 1f, fontScale = 1f)
                ) {
                    content()
                }
            }
        }

        // Hidden ScrollView wrapper prevents WindowManager from hardware-clipping
        // the ComposeView to the device's physical screen bounds when measuring tall
        // receipts. Translation off-screen + alpha 0 keeps it invisible but laid out.
        val wrapper = android.widget.ScrollView(parentView.context).apply {
            alpha = 0f
            translationX = 5000f
            addView(view)
        }
        (parentView.rootView as? ViewGroup)?.addView(wrapper)
            ?: run {
                Log.w("BITMAP_RENDERER", "rootView is not a ViewGroup; cannot attach ComposeView")
                return@withContext null
            }

        // Wait for Compose to compose and lay out. One pre-draw guarantees the
        // composer has produced a tree; one extra frame guarantees the layout pass
        // for that tree has run. Without these waits, the synchronous measure()
        // below frequently sees measuredHeight = 0.
        awaitPreDraw(view)
        awaitFrame()

        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            PRINTER_PAPER_WIDTH_PX,
            View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        val w = view.measuredWidth
        val h = view.measuredHeight

        if (h <= 0) {
            Log.w("BITMAP_RENDERER", "Composed view has zero height after layout; nothing to render.")
            (parentView.rootView as? ViewGroup)?.removeView(wrapper)
            return@withContext null
        }

        view.layout(0, 0, w, h)
        Composed(view, wrapper, w, h)
    }

    private suspend fun awaitPreDraw(view: View) {
        suspendCancellableCoroutine<Unit> { cont ->
            val observer = view.viewTreeObserver
            if (!observer.isAlive) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val o = view.viewTreeObserver
                    if (o.isAlive) o.removeOnPreDrawListener(this)
                    if (cont.isActive) cont.resume(Unit)
                    return true
                }
            }
            observer.addOnPreDrawListener(listener)
            cont.invokeOnCancellation {
                val o = view.viewTreeObserver
                if (o.isAlive) o.removeOnPreDrawListener(listener)
            }
            // Force a layout/draw pass so onPreDraw actually fires.
            view.invalidate()
        }
    }

    private data class Composed(
        val composeView: ComposeView,
        val wrapper: ViewGroup,
        val width: Int,
        val height: Int
    )

    // ───────────────── Legacy helpers (deprecated) ──────────────────

    /**
     * Legacy entry point. Prefer [renderFullReceiptBitmap] + [sliceBitmap] —
     * this composes the entire receipt for every chunk request and is racy for
     * tall content.
     */
    @Deprecated(
        message = "Compose-per-chunk is racy and non-deterministic. " +
            "Render once with renderFullReceiptBitmap() then sliceBitmap().",
        replaceWith = ReplaceWith("renderFullReceiptBitmap(parentView, content)")
    )
    suspend fun renderReceiptChunk(
        parentView: View,
        chunkIndex: Int,
        chunkSizePx: Int,
        content: @Composable () -> Unit
    ): Bitmap? {
        val full = renderFullReceiptBitmap(parentView, content) ?: return null
        // NOTE: do NOT recycle `full` here — sliceBitmap may return a sub-bitmap
        // that shares pixel storage with `full`, and recycling the parent would
        // corrupt the slice. Callers of this deprecated API will leak `full` until
        // GC; migrate to renderFullReceiptBitmap + sliceBitmap to take explicit
        // ownership of the parent bitmap's lifecycle.
        return sliceBitmap(full, chunkIndex, chunkSizePx)
    }

    /** Legacy single-shot render. Routes through the same compose-once pipeline. */
    suspend fun renderComposableToBitmap(
        parentView: View,
        content: @Composable () -> Unit
    ): Bitmap {
        return renderFullReceiptBitmap(parentView, content)
            ?: Bitmap.createBitmap(PRINTER_PAPER_WIDTH_PX, 1, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.WHITE)
            }
    }
}
