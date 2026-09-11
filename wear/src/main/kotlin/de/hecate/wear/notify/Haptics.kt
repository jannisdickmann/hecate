package de.hecate.wear.notify

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * US-7: the deviation alert is a vibration, not a screen takeover. The user is
 * running; the point is to be told without looking.
 */
object Haptics {

    /** Two firm pulses — distinguishable from a notification buzz. */
    fun deviation(context: Context) {
        vibrator(context)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 350, 180, 350), -1),
        )
    }

    /** One short pulse, used for start/stop confirmation. */
    fun tick(context: Context) {
        vibrator(context)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrator(context: Context): Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
}
