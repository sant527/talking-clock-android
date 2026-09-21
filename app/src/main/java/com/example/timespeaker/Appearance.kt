package com.example.timespeaker

import android.app.Activity
import android.content.pm.ActivityInfo

/** The colour palettes the app ships with. */
enum class AppTheme(
    val key: String,
    val styleRes: Int,
    val labelRes: Int,
    val isLight: Boolean,
    val accentRes: Int,
    val backgroundRes: Int
) {
    MIDNIGHT("midnight", R.style.Theme_TimeSpeaker_Midnight, R.string.theme_midnight, false,
        R.color.midnight_accent, R.color.midnight_bg),
    EMBER("ember", R.style.Theme_TimeSpeaker_Ember, R.string.theme_ember, false,
        R.color.ember_accent, R.color.ember_bg),
    FOREST("forest", R.style.Theme_TimeSpeaker_Forest, R.string.theme_forest, false,
        R.color.forest_accent, R.color.forest_bg),
    PAPER("paper", R.style.Theme_TimeSpeaker_Paper, R.string.theme_paper, true,
        R.color.paper_accent, R.color.paper_bg),
    SEPIA("sepia", R.style.Theme_TimeSpeaker_Sepia, R.string.theme_sepia, true,
        R.color.sepia_accent, R.color.sepia_bg);

    companion object {
        val DEFAULT = MIDNIGHT

        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * The two independently configurable voices.
 *
 * [MAIN] keeps the original, unsuffixed preference keys so settings made before the countdown
 * existed carry over untouched.
 */
enum class SpeechProfile(val keySuffix: String, val labelRes: Int) {
    MAIN("", R.string.profile_announcements),
    COUNTDOWN("_countdown", R.string.profile_countdown)
}

/** Whether the clock is drawn as digits or as a dial. */
enum class ClockStyle(val key: String, val labelRes: Int) {
    DIGITAL("digital", R.string.clock_digital),
    ANALOG("analog", R.string.clock_analog);

    companion object {
        val DEFAULT = DIGITAL

        fun fromKey(key: String?): ClockStyle = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Screen orientation for the full-screen clock. */
enum class ScreenOrientation(val key: String, val labelRes: Int, val request: Int) {
    AUTO("auto", R.string.orientation_auto, ActivityInfo.SCREEN_ORIENTATION_USER),
    PORTRAIT("portrait", R.string.orientation_portrait, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE("landscape", R.string.orientation_landscape, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    companion object {
        val DEFAULT = AUTO

        fun fromKey(key: String?): ScreenOrientation = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Applies the stored theme.
 *
 * Must run before `setContentView`, because a theme change after the views are inflated leaves
 * them holding the old colours.
 */
fun Activity.applyStoredTheme() {
    setTheme(Prefs.theme(this).styleRes)
}

/**
 * Pins an ordinary screen to portrait.
 *
 * Only the full-screen clock is allowed to turn sideways. SCREEN_ORIENTATION_USER is not enough
 * here: with auto-rotate switched off the device keeps a locked rotation, and once the clock has
 * forced landscape that locked value is landscape — so "follow the user" would leave every screen
 * stuck sideways after leaving the clock.
 */
fun Activity.lockToPortrait() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
