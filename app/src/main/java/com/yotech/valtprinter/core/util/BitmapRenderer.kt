package com.yotech.valtprinter.core.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BitmapRenderer {
    /**
     * Renders a specific "Slice" of a receipt.
     * This is the "Gold Standard" for resilience, allowing the app to checkpoint its progress.
     */
    suspend fun renderReceiptChunk(
        parentView: View,
        chunkIndex: Int,
        chunkSizePx: Int,
        content: @Composable () -> Unit
    ): Bitmap? {
        val (width, totalHeight, composeView) = prepareComposeView(parentView, content)

        val startY = chunkIndex * chunkSizePx
        if (startY >= totalHeight) return null // End of receipt

        val currentChunkHeight = if (startY + chunkSizePx > totalHeight) {
            totalHeight - startY
        } else {
            chunkSizePx
        }

        return withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(width, currentChunkHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            // Shift the canvas up to capture the specific slice
            canvas.translate(0f, -startY.toFloat())

            withContext(Dispatchers.Main) {
                composeView.draw(canvas)
                (parentView.rootView as? ViewGroup)?.removeView(composeView)
            }
            bitmap
        }
    }

    /**
     * Helper to prepare and measure the off-screen ComposeView.
     */
    private suspend fun prepareComposeView(
        parentView: View,
        content: @Composable () -> Unit
    ): Triple<Int, Int, ComposeView> = withContext(Dispatchers.Main) {
        val view = ComposeView(parentView.context).apply {
            alpha = 0f
            translationX = 5000f
            setViewTreeLifecycleOwner(parentView.findViewTreeLifecycleOwner())
            setViewTreeViewModelStoreOwner(parentView.findViewTreeViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(parentView.findViewTreeSavedStateRegistryOwner())

            setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)
                ) {
                    content()
                }
            }
            layoutParams = ViewGroup.LayoutParams(576, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        (parentView.rootView as? ViewGroup)?.addView(view)
        
        val measureSpecWidth = View.MeasureSpec.makeMeasureSpec(576, View.MeasureSpec.EXACTLY)
        val measureSpecHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(measureSpecWidth, measureSpecHeight)
        
        val w = view.measuredWidth
        val h = view.measuredHeight
        view.layout(0, 0, w, h)

        Triple(w, h, view)
    }

    suspend fun renderComposableToBitmap(
        parentView: View,
        content: @Composable () -> Unit
    ): Bitmap {
        val (width, height, composeView) = prepareComposeView(parentView, content)

        return withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            withContext(Dispatchers.Main) {
                composeView.draw(canvas)
                (parentView.rootView as? ViewGroup)?.removeView(composeView)
            }
            bitmap
        }
    }
}
