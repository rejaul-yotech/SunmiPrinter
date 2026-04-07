package com.yotech.valtprinter.core.util

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object AlarmHelper {

    private var isPlaying = false

    fun startAlarmAndVibration(context: Context) {
        if (isPlaying) return
        isPlaying = true

        try {
            // Purely Haptic Alert: No annoying audio tones
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Alternating pulse: wait 500ms, vibrate 500ms, repeat indefinitely
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(500, 500), 0)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(500, 500), 0)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopAlarmAndVibration(context: Context) {
        if (!isPlaying) return
        isPlaying = false

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
