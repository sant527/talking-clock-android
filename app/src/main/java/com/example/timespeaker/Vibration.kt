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

    fun buzz(context: Context, millis: Long, repeats: Int = 1) {
        val device = vibrator(context) ?: return
        val effect = effectFor(millis, repeats.coerceAtLeast(1))

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

    /**
     * One buzz, or several separated by a pause.
     *
     * Without the gap, repeated buzzes run together into one long vibration and the count is
     * impossible to feel.
     */
    private fun effectFor(millis: Long, repeats: Int): VibrationEffect {
        if (repeats == 1) {
            return VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)
        }

        // Waveform timings alternate off/on, starting with a zero-length pause.
        val timings = LongArray(repeats * 2)
        val amplitudes = IntArray(repeats * 2)
        for (i in 0 until repeats) {
            timings[i * 2] = if (i == 0) 0L else GAP_MS
            amplitudes[i * 2] = 0
            timings[i * 2 + 1] = millis
            amplitudes[i * 2 + 1] = VibrationEffect.DEFAULT_AMPLITUDE
        }
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    /** Long enough to feel as a separate buzz rather than a stutter. */
    private const val GAP_MS = 350L

    /** Cuts a vibration short, for the key-press escape hatch. */
    fun cancel(context: Context) {
        vibrator(context)?.cancel()
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
