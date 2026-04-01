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
    ): Bitmap = withContext(Dispatchers.Main) {
        val composeView = ComposeView(parentView.context).apply {
            setViewTreeLifecycleOwner(parentView.findViewTreeLifecycleOwner())
            setViewTreeViewModelStoreOwner(parentView.findViewTreeViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(parentView.findViewTreeSavedStateRegistryOwner())

            setContent(content)
            layoutParams = ViewGroup.LayoutParams(384, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val rootView = parentView.rootView as? ViewGroup
        rootView?.addView(composeView)

        val measureSpecWidth = View.MeasureSpec.makeMeasureSpec(384, View.MeasureSpec.EXACTLY)
        val measureSpecHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        composeView.measure(measureSpecWidth, measureSpecHeight)
        val measuredWidth = composeView.measuredWidth
        val measuredHeight = composeView.measuredHeight

        composeView.layout(0, 0, measuredWidth, measuredHeight)

        val bitmap = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        composeView.draw(canvas)

        rootView?.removeView(composeView)

        bitmap
    }
}
