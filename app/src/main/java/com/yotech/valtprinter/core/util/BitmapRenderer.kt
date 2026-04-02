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
    suspend fun renderComposableToBitmap(
        parentView: View,
        content: @Composable () -> Unit
    ): Bitmap {
        // 1. Create and measure the view on the Main thread
        val (width, height, composeView) = withContext(Dispatchers.Main) {
            val view = ComposeView(parentView.context).apply {
                // [Stealth Mode] - Hide the capture view from the user
                alpha = 0f
                translationX = 5000f // Move off-screen in case alpha isn't enough

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

            val rootView = parentView.rootView as? ViewGroup
            rootView?.addView(view)

            val measureSpecWidth = View.MeasureSpec.makeMeasureSpec(576, View.MeasureSpec.EXACTLY)
            val measureSpecHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

            view.measure(measureSpecWidth, measureSpecHeight)
            val measuredWidth = view.measuredWidth
            val measuredHeight = view.measuredHeight

            view.layout(0, 0, measuredWidth, measuredHeight)

            Triple(measuredWidth, measuredHeight, view)
        }

        // 2. Perform the actual drawing and bitmap creation
        return withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            // Draw must still happen on Main for some View types, but we minimize the window
            withContext(Dispatchers.Main) {
                composeView.draw(canvas)
                (parentView.rootView as? ViewGroup)?.removeView(composeView)
            }

            bitmap
        }
    }
}
