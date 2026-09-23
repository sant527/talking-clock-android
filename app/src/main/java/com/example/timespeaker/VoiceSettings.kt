package com.example.timespeaker

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Applies the stored voice, pitch and speed to a speech engine, and lists what a device offers.
 *
 * Android exposes no gender field on [Voice], so voices cannot be honestly labelled "male" or
 * "female" — they are listed by their engine names for preview instead, and pitch is the control
 * that actually shifts a voice deeper or lighter.
 */
object VoiceSettings {

    fun applyTo(
        engine: TextToSpeech,
        context: Context,
        profile: SpeechProfile = SpeechProfile.MAIN
    ) {
        engine.setPitch(Prefs.pitchPercent(context, profile) / 100f)
        engine.setSpeechRate(Prefs.ratePercent(context, profile) / 100f)

        val chosen = Prefs.voiceName(context, profile)?.takeUnless { it in EXCLUDED_VOICES }
        if (chosen == null) {
            // No voice picked for this profile: leave the engine on its default rather than
            // inheriting whatever the previous utterance happened to select.
            engine.defaultVoice?.let { engine.voice = it }
            return
        }
        engine.voices
            ?.firstOrNull { it.name == chosen }
            ?.let { engine.voice = it }
    }

    /**
     * Voices usable for announcements, in the device's own language.
     *
     * Voices flagged `notInstalled` are dropped: the engine lists them as available for download,
     * but selecting one gets you silence or a silent fallback. Offline voices come first, because
     * an announcer that goes quiet when the phone loses signal is worse than a plainer one.
     */
    fun availableVoices(engine: TextToSpeech): List<Voice> {
        val defaultLocale = engine.defaultVoice?.locale
        val language = defaultLocale?.language ?: DEFAULT_LANGUAGE
        val country = defaultLocale?.country
        val voices = engine.voices ?: return emptyList()

        return voices
            .filter { it.locale.language == language }
            // Own country only. The engine lists every English accent it knows - dozens of them -
            // and mixing Australian and Nigerian voices into an Indian phone's picker is noise.
            .filter { country.isNullOrBlank() || it.locale.country == country }
            .filterNot { NOT_INSTALLED in it.features }
            .filterNot { it.name in EXCLUDED_VOICES }
            .sortedWith(
                // The phone's own accent first, then offline before online, then by name so the
                // numbering stays put between visits.
                compareByDescending<Voice> { it.locale.country == defaultLocale?.country }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { it.name }
            )
            .take(MAX_VOICES)
    }

    /** A human label for a voice's accent, e.g. "India" or "United Kingdom". */
    fun accentOf(voice: Voice): String =
        voice.locale.getDisplayCountry(Locale.getDefault()).ifBlank { voice.locale.displayName }

    /**
     * Voices left out of the picker at the user's request.
     *
     * Listed by engine name rather than by position: the numbering shown in the app comes from
     * the engine's own list, and excluding by index would quietly remove the wrong ones if that
     * list ever changed. These names are specific to this device's Google TTS install - on an
     * engine that does not have them, the list simply does not match and nothing is removed.
     */
    private val EXCLUDED_VOICES = setOf(
        "en-in-x-ene-local",
        "en-in-x-ena-network",
        "en-in-x-enc-network"
    )

    private const val NOT_INSTALLED = "notInstalled"
    private const val DEFAULT_LANGUAGE = "en"
    private const val MAX_VOICES = 12
}
