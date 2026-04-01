package com.yotech.valtprinter.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val AppColorScheme = darkColorScheme(
    primary = CyanElectric,
    secondary = VioletElectric,
    background = NavyDeep,
    surface = NavySurface,
    onPrimary = NavyDeep,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = CrimsonError,
    onError = TextPrimary
)

@Composable
fun ValtPrinterTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = AppColorScheme.background.toArgb()
            window.navigationBarColor = AppColorScheme.background.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}