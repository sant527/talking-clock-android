package com.example.timespeaker

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * The single audio path for every announcement, whether it comes from the service or from a
 * preview on the settings screen.
 *
 * Shared deliberately: when the preview and the real announcement build their own speech
 * parameters, the preview stops being a preview.
 */
class SpeechOutput(private val context: Context) {

    private var boost: LoudnessEnhancer? = null

    /** True once [LoudnessEnhancer] has turned out to be unavailable on this device. */
    var boostUnsupported: Boolean = false
        private set

    /**
     * Speaks [text], optionally [repeats] times back to back.
     *
     * The first utterance flushes whatever came before; the rest queue behind it, because a
     * second FLUSH would simply cancel the first and you would hear the time once.
     */
    fun speak(
        engine: TextToSpeech,
        text: String,
        utteranceId: String,
        profile: SpeechProfile = SpeechProfile.MAIN,
        repeats: Int = 1
    ) {
        VoiceSettings.applyTo(engine, context, profile)

        val percent = Prefs.volumePercent(context, profile)

        // Announcements play on their own audio session so the amplifier can be attached to them
        // alone, leaving music and everything else on the device untouched.
        val sessionId = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
        applyBoost(sessionId, percent)

        // KEY_PARAM_VOLUME scales the utterance against the device's media volume rather than
        // replacing it. It saturates at 1.0; anything above that is the amplifier's job.
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, percent.coerceAtMost(100) / 100f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, sessionId)
        }
        repeat(repeats.coerceAtLeast(1)) { index ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(text, mode, params, "$utteranceId-$index")
        }
    }

    /**
     * Attaches a [LoudnessEnhancer] for volumes above 100%.
     *
     * The speech engine cannot be told to play louder than the device's media volume, so extra
     * loudness has to come from amplifying the output. LoudnessEnhancer applies make-up gain with
     * built-in limiting, which keeps a shouted announcement from clipping.
     */
    private fun applyBoost(sessionId: Int, percent: Int) {
        release()
        if (percent <= 100) return

        val gainMillibels = ((percent - 100) / BOOST_RANGE_PERCENT * MAX_BOOST_MILLIBELS).toInt()
        boost = try {
            LoudnessEnhancer(sessionId).apply {
                setTargetGain(gainMillibels)
                enabled = true
            }
        } catch (error: RuntimeException) {
            // Some devices and ROMs ship without the effect. Falling back to unboosted audio is
            // better than dropping the announcement.
            boostUnsupported = true
            null
        }
    }

    fun release() {
        boost?.release()
        boost = null
    }

    private companion object {
        /** Gain applied at the 400% end of the slider, in millibels (+40 dB). */
        const val MAX_BOOST_MILLIBELS = 4000f

        /** Slider span over which that gain is applied: 100% to 400%. */
        const val BOOST_RANGE_PERCENT = 300f
    }
}
