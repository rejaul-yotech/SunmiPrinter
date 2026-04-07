package com.yotech.valtprinter.core.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedbackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Light single sharp vibration "tap" (100ms) for successful Handshake.
     */
    fun emitSuccess() {
        vibrate(100)
    }

    /**
     * Silent double vibration for initial graceful offline state (Silent Guardian).
     */
    fun emitGracefulWarning() {
        vibrate(longArrayOf(0, 150, 150, 150))
    }

    /**
     * elite "single aggressive double-tap" haptic feedback for critical failures.
     */
    fun emitCriticalWarning() {
        // pattern: [delay, vib1, pause, vib2]
        // Aggressive: sharp 100ms followed by a deeper 300ms pulse
        vibrate(longArrayOf(0, 100, 50, 300))
    }

    private fun vibrate(duration: Long) {
        try {
            val vibrator = getVibrator()
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (e: Exception) {
            Log.e("FEEDBACK_DEBUG", "Vibration failed: ${e.message}")
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val vibrator = getVibrator()
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e("FEEDBACK_DEBUG", "Pattern vibration failed: ${e.message}")
        }
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
