package com.example.timespeaker

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The single vibration path, shared by announcements and the settings preview.
 *
 * Shared deliberately: when the preview builds its own vibration request, it stops being a
 * preview. The first version did exactly that and was discarded by the system while real
 * announcements worked.
 */
object Vibration {

    fun buzz(context: Context, millis: Long) {
        val device = vibrator(context) ?: return
        val effect = VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)

        // USAGE_ALARM matters more than it looks. Without attributes a vibration counts as
        // "unknown" usage, which Android drops entirely on a phone that has haptic feedback
        // switched off — which is exactly the phone this feature exists for.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        @Suppress("DEPRECATION")
        device.vibrate(effect, attributes)
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
