package com.example.timespeaker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.timespeaker.databinding.ActivityMainBinding
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Displays the clock and drives [TimeAnnouncerService].
 *
 * The activity never speaks. All announcements come from the service, so there is exactly one
 * voice whether the app is on screen, in the background, or the phone is locked.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var timeVisible = true
    private var analog = false
    private val appliedTheme by lazy { Prefs.theme(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, millisUntilNextSecond())
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The service runs either way; without the permission Android simply hides its
            // notification, so there is nothing to recover from here.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lockToPortrait()

        analog = Prefs.clockStyle(this) == ClockStyle.ANALOG

        binding.toggleVisibilityButton.setOnClickListener { setTimeVisible(!timeVisible) }
        binding.toggleSpeechButton.setOnClickListener { setAnnouncing(!TimeAnnouncerService.isRunning) }
        binding.speakNowButton.setOnClickListener { TimeAnnouncerService.speakNow(this) }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.fullscreenButton.setOnClickListener {
            startActivity(Intent(this, FullscreenClockActivity::class.java))
        }

        setTimeVisible(true)
        setUpQuickControls()

        askForNotificationPermission()
        // Opening the app starts the announcements, as before; they now outlive it.
        if (!TimeAnnouncerService.isRunning) TimeAnnouncerService.start(this)
    }

    private fun setUpQuickControls() {
        QuickControls.populateThemeSwatches(binding.themeSwatchRow, Prefs.theme(this)) { theme ->
            Prefs.setTheme(this, theme)
            // A theme swap cannot repaint live views; the window has to be rebuilt.
            recreate()
        }

        binding.clockStyleQuickGroup.check(
            if (analog) binding.quickAnalogButton.id else binding.quickDigitalButton.id
        )
        binding.clockStyleQuickGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val style = if (checkedId == binding.quickAnalogButton.id) ClockStyle.ANALOG else ClockStyle.DIGITAL
            Prefs.setClockStyle(this, style)
            // Swapping the face is just a visibility change, so no rebuild is needed.
            analog = style == ClockStyle.ANALOG
            setTimeVisible(timeVisible)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        // Theme and clock face can both have changed on the settings screen.
        if (appliedTheme != Prefs.theme(this)) {
            recreate()
            return
        }
        analog = Prefs.clockStyle(this) == ClockStyle.ANALOG
        binding.clockStyleQuickGroup.check(
            if (analog) binding.quickAnalogButton.id else binding.quickDigitalButton.id
        )
        setTimeVisible(timeVisible)
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun onTick() {
        val now = LocalTime.now()
        if (analog) binding.analogClock.setTime(now) else binding.clockText.text = CLOCK_FORMAT.format(now)
        refreshControls(now)
    }

    private fun refreshControls(now: LocalTime) {
        val running = TimeAnnouncerService.isRunning
        val interval = Prefs.intervalMinutes(this)
        binding.taglineText.text = resources.getQuantityString(R.plurals.tagline, interval, interval)

        binding.toggleSpeechButton.setText(
            if (running) R.string.speaking_on else R.string.speaking_off
        )
        binding.speakNowButton.isEnabled = running
        binding.footnoteText.visibility = if (running) View.VISIBLE else View.INVISIBLE

        binding.statusText.text = when {
            TimeAnnouncerService.ttsFailed -> getString(R.string.status_tts_failed)
            running -> getString(
                R.string.status_next,
                STATUS_FORMAT.format(
                    TimeAnnouncerService.nextMark(now, Prefs.intervalMinutes(this))
                )
            )
            else -> getString(R.string.status_muted)
        }
    }

    private fun setTimeVisible(visible: Boolean) {
        timeVisible = visible
        binding.clockText.visibility = if (visible && !analog) View.VISIBLE else View.GONE
        binding.analogClock.visibility = if (visible && analog) View.VISIBLE else View.GONE
        binding.hiddenText.visibility = if (visible) View.GONE else View.VISIBLE
        binding.toggleVisibilityButton.setText(
            if (visible) R.string.hide_time else R.string.show_time
        )
    }

    private fun setAnnouncing(enabled: Boolean) {
        if (enabled) TimeAnnouncerService.start(this) else TimeAnnouncerService.stop(this)
        refreshControls(LocalTime.now())
    }

    /** Keeps the seconds display in step with the system clock instead of drifting. */
    private fun millisUntilNextSecond(): Long =
        if (analog) ANALOG_FRAME_MS else 1000L - (System.currentTimeMillis() % 1000L)

    private companion object {
        /** The dial's second hand sweeps, so it is redrawn far more often than a digit changes. */
        const val ANALOG_FRAME_MS = 50L

        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
        val STATUS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    }
}
