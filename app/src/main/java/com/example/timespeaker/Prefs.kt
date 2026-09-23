package com.example.timespeaker

import android.content.Context

/**
 * The settings shared between the UI and [TimeAnnouncerService].
 *
 * Stored rather than passed in an intent so the service still has them after the system
 * restarts it on its own.
 */
object Prefs {

    private const val FILE = "time_speaker"
    private const val KEY_VOLUME = "announcement_volume"
    private const val KEY_PITCH = "announcement_pitch"
    private const val KEY_RATE = "announcement_rate"
    private const val KEY_VOICE = "announcement_voice"
    private const val KEY_COUNTDOWN_SAME_VOICE = "countdown_same_voice"
    private const val KEY_FEEDBACK = "announcement_feedback"
    private const val KEY_VIBRATION_MS = "vibration_millis"
    private const val KEY_INTERVAL_ENABLED = "interval_enabled"
    private const val KEY_MAJOR_ENABLED = "major_enabled"
    private const val KEY_MAJOR_INTERVAL = "major_interval"
    private const val KEY_MAJOR_REPEATS = "major_repeats"
    private const val KEY_SPEAK_REPEATS = "speak_repeats"
    private const val KEY_VIBRATION_REPEATS = "vibration_repeats"
    private const val KEY_GENDER_PREFIX = "voice_gender_"
    private const val KEY_SORT_BY_LABEL = "voice_sort_by_label"
    private const val KEY_THEME = "app_theme"
    private const val KEY_CLOCK_STYLE = "clock_style"
    private const val KEY_ORIENTATION = "fullscreen_orientation"
    private const val KEY_INTERVAL = "announcement_interval"
    private const val KEY_COUNTDOWN = "countdown_enabled"

    const val MALE = "M"
    const val FEMALE = "F"

    const val DEFAULT_INTERVAL_MINUTES = 5

    /** Major announcements land on their own marks, independent of the ordinary interval. */
    const val DEFAULT_MAJOR_INTERVAL_MINUTES = 15

    /** All divide 60, so major marks stay aligned to the top of the hour. */
    val MAJOR_INTERVAL_CHOICES = listOf(10, 15, 20, 30, 60)
    const val DEFAULT_MAJOR_REPEATS = 3
    const val MIN_REPEATS = 1
    const val MAX_SPEAK_REPEATS = 5

    /** Buzzes go much higher than spoken repeats: a long pulse train is a usable silent alarm. */
    const val MAX_VIBRATION_REPEATS = 20

    /** Vibration length, in tenths of a second, as the slider works in half-second steps. */
    const val DEFAULT_VIBRATION_MS = 1000
    const val MIN_VIBRATION_MS = 500
    const val MAX_VIBRATION_MS = 10000

    /** Intervals offered in settings. All divide 60, so marks line up with the top of the hour. */
    val INTERVAL_CHOICES = listOf(1, 5, 10, 15, 30, 60)

    const val DEFAULT_VOLUME_PERCENT = 100
    const val MAX_VOLUME_PERCENT = 400

    /** Pitch and rate are stored as percentages; 100 is the engine's natural setting. */
    const val DEFAULT_PITCH_PERCENT = 100
    const val DEFAULT_RATE_PERCENT = 95
    const val MIN_TONE_PERCENT = 50
    const val MAX_TONE_PERCENT = 200

    /**
     * Announcement loudness, 0–400.
     *
     * Up to 100 this is a share of the device's media volume. Above 100 the speech engine is
     * already at its ceiling, so the extra is applied as amplifier gain instead — see
     * [TimeAnnouncerService].
     */
    fun volumePercent(context: Context, profile: SpeechProfile = SpeechProfile.MAIN): Int =
        prefs(context).getInt(key(KEY_VOLUME, profile), DEFAULT_VOLUME_PERCENT)
            .coerceIn(0, MAX_VOLUME_PERCENT)

    fun setVolumePercent(context: Context, percent: Int, profile: SpeechProfile = SpeechProfile.MAIN) =
        putInt(context, key(KEY_VOLUME, profile), percent.coerceIn(0, MAX_VOLUME_PERCENT))

    /** Lower pitch reads as a deeper voice, higher as a lighter one. */
    fun pitchPercent(context: Context, profile: SpeechProfile = SpeechProfile.MAIN): Int =
        tone(context, key(KEY_PITCH, profile), DEFAULT_PITCH_PERCENT)

    fun setPitchPercent(context: Context, percent: Int, profile: SpeechProfile = SpeechProfile.MAIN) =
        putTone(context, key(KEY_PITCH, profile), percent)

    fun ratePercent(context: Context, profile: SpeechProfile = SpeechProfile.MAIN): Int =
        tone(context, key(KEY_RATE, profile), DEFAULT_RATE_PERCENT)

    fun setRatePercent(context: Context, percent: Int, profile: SpeechProfile = SpeechProfile.MAIN) =
        putTone(context, key(KEY_RATE, profile), percent)

    /** The chosen [android.speech.tts.Voice] name, or null to let the engine pick. */
    fun voiceName(context: Context, profile: SpeechProfile = SpeechProfile.MAIN): String? =
        prefs(context).getString(key(KEY_VOICE, profile), null)

    fun setVoiceName(context: Context, name: String?, profile: SpeechProfile = SpeechProfile.MAIN) {
        prefs(context).edit().apply {
            val k = key(KEY_VOICE, profile)
            if (name == null) remove(k) else putString(k, name)
        }.apply()
    }

    /** Whether the countdown borrows the announcements' voice, volume, pitch and speed. */
    fun countdownUsesMainVoice(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COUNTDOWN_SAME_VOICE, true)

    fun setCountdownUsesMainVoice(context: Context, same: Boolean) {
        prefs(context).edit().putBoolean(KEY_COUNTDOWN_SAME_VOICE, same).apply()
    }

    /**
     * The profile whose settings actually apply for [requested].
     *
     * Resolved in one place so nothing has to remember to check the "same voice" switch — asking
     * for the countdown profile simply gives back the main one while that switch is on.
     */
    fun effectiveProfile(context: Context, requested: SpeechProfile): SpeechProfile =
        if (requested == SpeechProfile.COUNTDOWN && countdownUsesMainVoice(context)) {
            SpeechProfile.MAIN
        } else {
            requested
        }

    private fun key(base: String, profile: SpeechProfile): String = base + profile.keySuffix

    /**
     * The Male/Female tag the user put on a voice, or null if untagged.
     *
     * Android exposes no gender for a [android.speech.tts.Voice], so rather than guessing from
     * engine-internal voice codes and getting it wrong, the label is whatever the user heard.
     */
    fun voiceGender(context: Context, voiceName: String): String? =
        prefs(context).getString(KEY_GENDER_PREFIX + voiceName, null)

    fun setVoiceGender(context: Context, voiceName: String, gender: String?) {
        prefs(context).edit().apply {
            val key = KEY_GENDER_PREFIX + voiceName
            if (gender == null) remove(key) else putString(key, gender)
        }.apply()
    }

    /** Whether the voice list is grouped by the user's M/F labels. */
    fun sortByLabel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SORT_BY_LABEL, false)

    fun setSortByLabel(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SORT_BY_LABEL, enabled).apply()
    }

    /** How often the full time is announced, in minutes. */
    fun intervalMinutes(context: Context): Int {
        val stored = prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES)
        return if (stored in INTERVAL_CHOICES) stored else DEFAULT_INTERVAL_MINUTES
    }

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, minutes).apply()
    }

    /**
     * Whether the minutes left until the next announcement are spoken on every minute in between
     * — just the number, so 7:51 with a five-minute interval says "four".
     */
    fun countdownEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COUNTDOWN, false)

    fun setCountdownEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COUNTDOWN, enabled).apply()
    }

    /** Whether announcements speak, vibrate, or both. Never applies to the countdown. */
    /** Whether the ordinary interval announcements run at all. */
    fun intervalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INTERVAL_ENABLED, true)

    fun setIntervalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INTERVAL_ENABLED, enabled).apply()
    }

    /** Whether a section speaks, vibrates, or both. Each section chooses for itself. */
    fun announcementFeedback(
        context: Context,
        profile: SpeechProfile = SpeechProfile.MAIN
    ): AnnouncementFeedback =
        AnnouncementFeedback.fromKey(prefs(context).getString(key(KEY_FEEDBACK, profile), null))

    fun setAnnouncementFeedback(
        context: Context,
        feedback: AnnouncementFeedback,
        profile: SpeechProfile = SpeechProfile.MAIN
    ) {
        prefs(context).edit().putString(key(KEY_FEEDBACK, profile), feedback.key).apply()
    }

    fun vibrationMillis(context: Context, profile: SpeechProfile = SpeechProfile.MAIN): Int =
        prefs(context).getInt(key(KEY_VIBRATION_MS, profile), DEFAULT_VIBRATION_MS)
            .coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)

    fun setVibrationMillis(
        context: Context,
        millis: Int,
        profile: SpeechProfile = SpeechProfile.MAIN
    ) = putInt(
        context,
        key(KEY_VIBRATION_MS, profile),
        millis.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)
    )

    /** True when at least one section is switched on, so the service has something to do. */
    fun anythingEnabled(context: Context): Boolean =
        intervalEnabled(context) || majorEnabled(context)

    /**
     * Whether the quarter hours get a major announcement.
     *
     * Deliberately not tied to the ordinary interval: the point is a stronger marker at :00,
     * :15, :30 and :45 whatever the interval happens to be.
     */
    fun majorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MAJOR_ENABLED, false)

    fun setMajorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAJOR_ENABLED, enabled).apply()
    }

    /** How often a major announcement lands, in minutes. */
    fun majorIntervalMinutes(context: Context): Int {
        val stored = prefs(context).getInt(KEY_MAJOR_INTERVAL, DEFAULT_MAJOR_INTERVAL_MINUTES)
        return if (stored in MAJOR_INTERVAL_CHOICES) stored else DEFAULT_MAJOR_INTERVAL_MINUTES
    }

    fun setMajorIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_MAJOR_INTERVAL, minutes).apply()
    }

    /**
     * How many times this announcement says the time.
     *
     * Majors default to three, everything else to one. A major set before repeats existed for
     * every section keeps whatever count it had.
     */
    fun speakRepeats(context: Context, profile: SpeechProfile = SpeechProfile.MAIN): Int {
        val fallback = if (profile == SpeechProfile.MAJOR) {
            prefs(context).getInt(KEY_MAJOR_REPEATS, DEFAULT_MAJOR_REPEATS)
        } else {
            1
        }
        return prefs(context).getInt(key(KEY_SPEAK_REPEATS, profile), fallback)
            .coerceIn(MIN_REPEATS, MAX_SPEAK_REPEATS)
    }

    fun setSpeakRepeats(context: Context, repeats: Int, profile: SpeechProfile = SpeechProfile.MAIN) =
        putInt(
            context,
            key(KEY_SPEAK_REPEATS, profile),
            repeats.coerceIn(MIN_REPEATS, MAX_SPEAK_REPEATS)
        )

    /** How many buzzes this announcement gives. */
    fun vibrationRepeats(context: Context, profile: SpeechProfile = SpeechProfile.MAIN): Int =
        prefs(context).getInt(key(KEY_VIBRATION_REPEATS, profile), 1)
            .coerceIn(MIN_REPEATS, MAX_VIBRATION_REPEATS)

    fun setVibrationRepeats(
        context: Context,
        repeats: Int,
        profile: SpeechProfile = SpeechProfile.MAIN
    ) = putInt(
        context,
        key(KEY_VIBRATION_REPEATS, profile),
        repeats.coerceIn(MIN_REPEATS, MAX_VIBRATION_REPEATS)
    )

    fun theme(context: Context): AppTheme =
        AppTheme.fromKey(prefs(context).getString(KEY_THEME, null))

    fun setTheme(context: Context, theme: AppTheme) {
        prefs(context).edit().putString(KEY_THEME, theme.key).apply()
    }

    fun clockStyle(context: Context): ClockStyle =
        ClockStyle.fromKey(prefs(context).getString(KEY_CLOCK_STYLE, null))

    fun setClockStyle(context: Context, style: ClockStyle) {
        prefs(context).edit().putString(KEY_CLOCK_STYLE, style.key).apply()
    }

    /** Orientation used by the full-screen clock only; the main screen follows the device. */
    fun orientation(context: Context): ScreenOrientation =
        ScreenOrientation.fromKey(prefs(context).getString(KEY_ORIENTATION, null))

    fun setOrientation(context: Context, orientation: ScreenOrientation) {
        prefs(context).edit().putString(KEY_ORIENTATION, orientation.key).apply()
    }

    private fun tone(context: Context, key: String, fallback: Int): Int =
        prefs(context).getInt(key, fallback).coerceIn(MIN_TONE_PERCENT, MAX_TONE_PERCENT)

    private fun putTone(context: Context, key: String, percent: Int) =
        putInt(context, key, percent.coerceIn(MIN_TONE_PERCENT, MAX_TONE_PERCENT))

    private fun putInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
