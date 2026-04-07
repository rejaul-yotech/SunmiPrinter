package com.yotech.valtprinter.core.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
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
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    /**
     * Single sharp vibration "tap" (100ms) + Success tone for reconnect.
     */
    fun emitSuccess() {
        vibrate(100)
        playTone(ToneGenerator.TONE_PROP_ACK)
    }

    /**
     * Silent double vibration for initial graceful offline state (Silent Guardian).
     */
    fun emitGracefulWarning() {
        vibrate(longArrayOf(0, 150, 150, 150))
    }

    /**
     * Double vibration pulse (200ms) + Warning chime for unexpected disconnect persisting.
     */
    fun emitCriticalWarning() {
        vibrate(longArrayOf(0, 200, 100, 200))
        playTone(ToneGenerator.TONE_PROP_NACK)
    }

    private fun playTone(toneType: Int) {
        try {
            toneGenerator.startTone(toneType, 200)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
