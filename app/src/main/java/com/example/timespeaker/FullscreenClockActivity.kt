package com.example.timespeaker

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.timespeaker.databinding.ActivityFullscreenBinding
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The clock alone, filling the screen, with the system bars hidden.
 *
 * Announcements are untouched here: they belong to [TimeAnnouncerService] and keep running
 * whatever is on screen.
 */
class FullscreenClockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullscreenBinding

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            showTime(LocalTime.now())
            handler.postDelayed(this, tickIntervalMillis())
        }
    }
    private val hideControls = Runnable { setControlsVisible(false) }

    private var analog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = Prefs.orientation(this).request

        analog = Prefs.clockStyle(this) == ClockStyle.ANALOG
        binding.fullClockText.visibility = if (analog) View.GONE else View.VISIBLE
        binding.fullAnalogClock.visibility = if (analog) View.VISIBLE else View.GONE

        hideSystemBars()
        keepClearOfDisplayCutout()

        binding.orientationGroup.check(buttonIdFor(Prefs.orientation(this)))
        binding.orientationGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val orientation = orientationFor(checkedId)
            Prefs.setOrientation(this, orientation)
            requestedOrientation = orientation.request
            scheduleControlsHide()
        }

        applyControlBackdrop()
        setUpQuickControls()

        binding.exitButton.setOnClickListener { finish() }

        // Tapping anywhere brings the controls back; they fade out again so the clock is clean.
        binding.fullscreenRoot.setOnClickListener { setControlsVisible(true) }
        scheduleControlsHide()
    }

    /**
     * Puts a panel behind the overlay controls.
     *
     * They sit directly on top of the clock — over the dial's numerals in analog mode — and are
     * close to unreadable without something to separate them from it.
     */
    private fun applyControlBackdrop() {
        val background = resolveThemeColor(android.R.attr.colorBackground)
        val scrim = ColorUtils.setAlphaComponent(background, BACKDROP_ALPHA)

        binding.controlBar.background = roundedPanel(scrim, PANEL_RADIUS_DP)
        binding.controlBar.setPadding(dp(16), dp(12), dp(16), dp(12))

        binding.hintText.background = roundedPanel(scrim, HINT_RADIUS_DP)
        binding.hintText.setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun roundedPanel(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun setUpQuickControls() {
        QuickControls.populateThemeSwatches(binding.fullThemeSwatchRow, Prefs.theme(this)) { theme ->
            Prefs.setTheme(this, theme)
            recreate()
        }

        binding.fullClockStyleGroup.check(
            if (analog) binding.fullAnalogButton.id else binding.fullDigitalButton.id
        )
        binding.fullClockStyleGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val style = if (checkedId == binding.fullAnalogButton.id) ClockStyle.ANALOG else ClockStyle.DIGITAL
            Prefs.setClockStyle(this, style)
            applyClockStyle(style)
            scheduleControlsHide()
        }
    }

    private fun applyClockStyle(style: ClockStyle) {
        analog = style == ClockStyle.ANALOG
        binding.fullClockText.visibility = if (analog) View.GONE else View.VISIBLE
        binding.fullAnalogClock.visibility = if (analog) View.VISIBLE else View.GONE
        // The two faces redraw at different rates, so restart the ticker on the new cadence.
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun finish() {
        // Release the forced rotation before leaving, so the screen underneath is not left sideways.
        lockToPortrait()
        super.finish()
    }

    private fun buttonIdFor(orientation: ScreenOrientation): Int = when (orientation) {
        ScreenOrientation.AUTO -> binding.autoButton.id
        ScreenOrientation.PORTRAIT -> binding.portraitButton.id
        ScreenOrientation.LANDSCAPE -> binding.landscapeButton.id
    }

    private fun orientationFor(buttonId: Int): ScreenOrientation = when (buttonId) {
        binding.portraitButton.id -> ScreenOrientation.PORTRAIT
        binding.landscapeButton.id -> ScreenOrientation.LANDSCAPE
        else -> ScreenOrientation.AUTO
    }

    private fun setControlsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.controlBar.visibility = visibility
        binding.hintText.visibility = visibility
        if (visible) scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
    }

    /**
     * Insets the whole screen by the display cutout.
     *
     * Drawing edge to edge is what makes the clock fill the screen, but in landscape the camera
     * notch sits over one end of it and would clip the digits.
     */
    private fun keepClearOfDisplayCutout() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.fullscreenRoot) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            insets
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showTime(now: LocalTime) {
        if (analog) {
            binding.fullAnalogClock.setTime(now)
        } else {
            binding.fullClockText.text = CLOCK_FORMAT.format(now)
        }
    }

    /** The dial's second hand sweeps, so it is redrawn far more often than a digit changes. */
    private fun tickIntervalMillis(): Long =
        if (analog) ANALOG_FRAME_MS else 1000L - (System.currentTimeMillis() % 1000L)

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(hideControls)
    }

    private companion object {
        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
        const val CONTROLS_TIMEOUT_MS = 4000L
        const val ANALOG_FRAME_MS = 50L

        /** Opaque enough to read against the dial, sheer enough to still see the clock behind. */
        const val BACKDROP_ALPHA = 235
        const val PANEL_RADIUS_DP = 22
        const val HINT_RADIUS_DP = 14
    }
}
