package com.example.timespeaker

import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.timespeaker.databinding.ActivitySettingsBinding
import com.example.timespeaker.databinding.ItemVoiceBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import java.time.LocalTime
import java.util.Locale

/**
 * Volume, tone and voice, each previewed as it is changed.
 *
 * This screen runs its own speech engine rather than routing previews through
 * [TimeAnnouncerService]: it needs the engine's voice list anyway, and a preview that responds
 * the instant a slider is released is the whole point of the screen.
 */
class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivitySettingsBinding

    private val output by lazy { SpeechOutput(this) }
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val voiceRadios = linkedMapOf<String, RadioButton>()

    /** Which voice the sliders and voice list below are currently editing. */
    private var profile = SpeechProfile.MAIN
    private var naturalOrder: List<Voice> = emptyList()
    private val themeRadios = linkedMapOf<AppTheme, RadioButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredTheme()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        lockToPortrait()

        setUpIntervalPicker()
        setUpFeedbackPicker()
        setUpCountdownSwitch()
        setUpProfileSwitcher()
        setUpThemePicker()
        setUpClockStylePicker()

        // Route the hardware volume keys at the stream announcements actually use, so the rocker
        // adjusts the right thing while this screen is open.
        volumeControlStream = AudioManager.STREAM_MUSIC

        setUpSlider(
            slider = binding.volumeSlider,
            value = Prefs.volumePercent(this, profile),
            onChange = { Prefs.setVolumePercent(this, it, profile); updateVolumeLabel(it) }
        )
        setUpSlider(
            slider = binding.pitchSlider,
            value = Prefs.pitchPercent(this, profile),
            onChange = { Prefs.setPitchPercent(this, it, profile); updateToneLabels() }
        )
        setUpSlider(
            slider = binding.rateSlider,
            value = Prefs.ratePercent(this, profile),
            onChange = { Prefs.setRatePercent(this, it, profile); updateToneLabels() }
        )

        loadProfileIntoControls()

        binding.previewButton.setOnClickListener { preview() }
        binding.resetButton.setOnClickListener { resetToDefaults() }

        tts = TextToSpeech(this, this)
    }

    private fun setUpIntervalPicker() {
        val current = Prefs.intervalMinutes(this)

        Prefs.INTERVAL_CHOICES.forEach { minutes ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = minutes.toString()
                minWidth = 0
                minimumWidth = 0
                tag = minutes
            }
            binding.intervalGroup.addView(button)
            if (minutes == current) binding.intervalGroup.check(button.id)
        }

        binding.intervalGroup.addOnButtonCheckedListener { group, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val minutes = group.findViewById<MaterialButton>(checkedId)?.tag as? Int
                ?: return@addOnButtonCheckedListener
            Prefs.setIntervalMinutes(this, minutes)
            // The alarm already in flight was set for the old interval; move it.
            TimeAnnouncerService.reschedule(this)
            refreshCountdownAvailability()
        }
    }

    private fun setUpFeedbackPicker() {
        binding.feedbackGroup.check(buttonFor(Prefs.announcementFeedback(this)))
        binding.feedbackGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setAnnouncementFeedback(this, feedbackFor(checkedId))
            refreshVibrationControls()
        }

        binding.vibrationSlider.value = Prefs.vibrationMillis(this).toFloat()
        binding.vibrationSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setVibrationMillis(this, value.toInt())
            updateVibrationLabel()
        }
        binding.vibrationSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            // Feeling the length you just chose is the only way to judge it.
            override fun onStopTrackingTouch(slider: Slider) = previewVibration()
        })

        binding.vibrationTestButton.setOnClickListener { previewVibration() }

        refreshVibrationControls()
    }

    private fun buttonFor(feedback: AnnouncementFeedback): Int = when (feedback) {
        AnnouncementFeedback.SPEAK -> binding.feedbackSpeakButton.id
        AnnouncementFeedback.SPEAK_AND_VIBRATE -> binding.feedbackBothButton.id
        AnnouncementFeedback.VIBRATE_ONLY -> binding.feedbackVibrateButton.id
    }

    private fun feedbackFor(buttonId: Int): AnnouncementFeedback = when (buttonId) {
        binding.feedbackBothButton.id -> AnnouncementFeedback.SPEAK_AND_VIBRATE
        binding.feedbackVibrateButton.id -> AnnouncementFeedback.VIBRATE_ONLY
        else -> AnnouncementFeedback.SPEAK
    }

    /** The length control is only meaningful when something actually vibrates. */
    private fun refreshVibrationControls() {
        val vibrates = Prefs.announcementFeedback(this).vibrates
        val visibility = if (vibrates) View.VISIBLE else View.GONE
        binding.vibrationLabel.visibility = visibility
        binding.vibrationSlider.visibility = visibility
        binding.vibrationTestButton.visibility = visibility
        if (vibrates) updateVibrationLabel()
    }

    private fun updateVibrationLabel() {
        val seconds = Prefs.vibrationMillis(this) / 1000f
        binding.vibrationLabel.text = getString(R.string.vibration_label, SECONDS_FORMAT.format(seconds))
    }

    /**
     * Buzzes for the chosen length, and says so while it runs.
     *
     * At the long end of the slider a silent button would look broken — ten seconds is long
     * enough that you need telling something is happening.
     */
    private fun previewVibration() {
        val millis = Prefs.vibrationMillis(this).toLong()
        Vibration.buzz(this, millis)

        binding.vibrationTestButton.setText(R.string.vibration_test_running)
        binding.vibrationTestButton.isEnabled = false
        binding.vibrationTestButton.postDelayed({
            binding.vibrationTestButton.setText(R.string.vibration_test)
            binding.vibrationTestButton.isEnabled = true
        }, millis)
    }

    private fun setUpCountdownSwitch() {
        binding.countdownSwitch.isChecked = Prefs.countdownEnabled(this)
        binding.countdownSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setCountdownEnabled(this, checked)
            TimeAnnouncerService.reschedule(this)
            refreshCountdownAvailability()
        }

        binding.sameVoiceSwitch.isChecked = Prefs.countdownUsesMainVoice(this)
        binding.sameVoiceSwitch.setOnCheckedChangeListener { _, same ->
            Prefs.setCountdownUsesMainVoice(this, same)
            // Editing a voice that is no longer in use would be confusing, so fall back to the
            // announcements one whenever the countdown stops having its own.
            if (same) selectProfile(SpeechProfile.MAIN)
            refreshCountdownAvailability()
        }

        refreshCountdownAvailability()
    }

    private fun setUpProfileSwitcher() {
        binding.profileGroup.check(buttonFor(profile))
        binding.profileGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectProfile(
                if (checkedId == binding.profileCountdownButton.id) SpeechProfile.COUNTDOWN
                else SpeechProfile.MAIN
            )
        }
    }

    private fun buttonFor(which: SpeechProfile): Int =
        if (which == SpeechProfile.COUNTDOWN) binding.profileCountdownButton.id
        else binding.profileMainButton.id

    private fun selectProfile(which: SpeechProfile) {
        if (profile == which) return
        profile = which
        binding.profileGroup.check(buttonFor(which))
        loadProfileIntoControls()
        if (ttsReady) renderVoices()
    }

    /** Pushes the selected profile's stored values back into the sliders and labels. */
    private fun loadProfileIntoControls() {
        binding.volumeSlider.value = Prefs.volumePercent(this, profile).toFloat()
        binding.pitchSlider.value = Prefs.pitchPercent(this, profile).toFloat()
        binding.rateSlider.value = Prefs.ratePercent(this, profile).toFloat()
        updateVolumeLabel(Prefs.volumePercent(this, profile))
        updateToneLabels()
    }

    /** At a one-minute interval there is nothing to count down to, so the switch is disabled. */
    private fun refreshCountdownAvailability() {
        val usable = Prefs.intervalMinutes(this) > 1
        binding.countdownSwitch.isEnabled = usable
        binding.countdownExplainer.setText(
            if (usable) R.string.countdown_explainer else R.string.countdown_needs_interval
        )

        // The voice switch and the profile tabs are only meaningful while a countdown is running
        // with settings of its own.
        val counting = usable && Prefs.countdownEnabled(this)
        binding.sameVoiceSwitch.isEnabled = counting
        binding.profileRow.visibility =
            if (counting && !Prefs.countdownUsesMainVoice(this)) View.VISIBLE else View.GONE
    }

    private fun setUpThemePicker() {
        val current = Prefs.theme(this)
        var lastWasLight: Boolean? = null

        AppTheme.entries.forEach { theme ->
            if (lastWasLight != theme.isLight) {
                binding.themeGroup.addView(groupHeading(
                    if (theme.isLight) R.string.theme_group_light else R.string.theme_group_dark
                ))
                lastWasLight = theme.isLight
            }

            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(theme.labelRes)
                isChecked = theme == current
                setTextColor(textColorPrimary())
                setOnClickListener {
                    if (Prefs.theme(this@SettingsActivity) == theme) return@setOnClickListener
                    Prefs.setTheme(this@SettingsActivity, theme)
                    // The whole window has to be rebuilt; a theme swap cannot repaint live views.
                    recreate()
                }
            }
            themeRadios[theme] = button
            binding.themeGroup.addView(button)
        }
    }

    private fun groupHeading(textRes: Int): TextView = TextView(this).apply {
        setText(textRes)
        textSize = 12f
        isAllCaps = true
        setTextColor(textColorSecondary())
        setPadding(0, if (binding.themeGroup.childCount == 0) 0 else 16, 0, 4)
    }

    private fun setUpClockStylePicker() {
        val digital = Prefs.clockStyle(this) == ClockStyle.DIGITAL
        binding.clockStyleGroup.check(
            if (digital) binding.digitalButton.id else binding.analogButton.id
        )
        binding.clockStyleGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val style = if (checkedId == binding.analogButton.id) ClockStyle.ANALOG else ClockStyle.DIGITAL
            Prefs.setClockStyle(this, style)
        }
    }

    private fun textColorPrimary(): Int = resolveThemeColor(android.R.attr.textColorPrimary)

    private fun textColorSecondary(): Int = resolveThemeColor(android.R.attr.textColorSecondary)

    private fun resolveThemeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun setUpSlider(slider: Slider, value: Int, onChange: (Int) -> Unit) {
        slider.value = value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        slider.addOnChangeListener { _, newValue, fromUser ->
            if (fromUser) onChange(newValue.toInt())
        }
        // Preview on release rather than on every step, so dragging isn't a stream of chatter.
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = preview()
        })
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            binding.voiceStatusText.setText(R.string.voice_unavailable)
            return
        }
        if (engine.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) {
            engine.setLanguage(Locale.US)
        }
        ttsReady = true
        showVoices(VoiceSettings.availableVoices(engine))
    }

    private fun showVoices(voices: List<Voice>) {
        if (voices.isEmpty()) {
            binding.voiceStatusText.setText(R.string.voice_none)
            binding.sortGroup.visibility = View.GONE
            return
        }

        binding.voiceStatusText.visibility = View.GONE
        // Numbering follows this order and never the displayed one, so "voice 8 is the male one"
        // stays true after the list is re-sorted.
        naturalOrder = voices

        binding.sortGroup.check(
            if (Prefs.sortByLabel(this)) binding.sortLabelButton.id else binding.sortAccentButton.id
        )
        binding.sortGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            Prefs.setSortByLabel(this, checkedId == binding.sortLabelButton.id)
            renderVoices()
        }

        renderVoices()

        // The checked radio button takes focus as it is added, and the ScrollView follows it,
        // which would land the user in the voice list with the sliders scrolled off the top.
        binding.scrollRoot.post { binding.scrollRoot.scrollTo(0, 0) }
    }

    private fun renderVoices() {
        binding.voiceGroup.removeAllViews()
        voiceRadios.clear()

        val stored = Prefs.voiceName(this, profile)
        val numbered = naturalOrder.mapIndexed { index, voice -> voice to index }
        val ordered = if (Prefs.sortByLabel(this)) {
            // Labelled voices group together, male first, with untagged voices last. Ties keep
            // their natural position so the list does not reshuffle arbitrarily.
            numbered.sortedWith(compareBy({ labelRank(it.first) }, { it.second }))
        } else {
            numbered
        }

        ordered.forEach { (voice, naturalIndex) ->
            addVoiceRow(
                voice = voice,
                index = naturalIndex,
                isSelected = voice.name == stored || (stored == null && naturalIndex == 0)
            )
        }
    }

    private fun labelRank(voice: Voice): Int = when (Prefs.voiceGender(this, voice.name)) {
        Prefs.MALE -> 0
        Prefs.FEMALE -> 1
        else -> 2
    }

    private fun addVoiceRow(voice: Voice, index: Int, isSelected: Boolean) {
        val row = ItemVoiceBinding.inflate(layoutInflater, binding.voiceGroup, false)

        row.voiceRadio.text = voiceLabel(voice, index)
        row.voiceRadio.isChecked = isSelected
        row.voiceRadio.setOnClickListener { selectVoice(voice) }

        // The group is not a RadioGroup any more — each row carries its own tag buttons — so
        // single selection is enforced here instead.
        voiceRadios[voice.name] = row.voiceRadio

        when (Prefs.voiceGender(this, voice.name)) {
            Prefs.MALE -> row.genderGroup.check(row.maleButton.id)
            Prefs.FEMALE -> row.genderGroup.check(row.femaleButton.id)
        }
        row.genderGroup.addOnButtonCheckedListener { group, checkedId, checked ->
            if (!checked && group.checkedButtonId == View.NO_ID) {
                Prefs.setVoiceGender(this, voice.name, null)
            } else if (checked) {
                val gender = if (checkedId == row.maleButton.id) Prefs.MALE else Prefs.FEMALE
                Prefs.setVoiceGender(this, voice.name, gender)
            }
            row.voiceRadio.text = voiceLabel(voice, index)
            if (Prefs.sortByLabel(this)) {
                // The row has just changed group; re-render so it moves where the sort says.
                binding.voiceGroup.post { renderVoices() }
            }
        }

        binding.voiceGroup.addView(row.root)
    }

    private fun voiceLabel(voice: Voice, index: Int): String {
        val accent = VoiceSettings.accentOf(voice)
        val gender = when (Prefs.voiceGender(this, voice.name)) {
            Prefs.MALE -> getString(R.string.male)
            Prefs.FEMALE -> getString(R.string.female)
            else -> null
        }
        return if (gender == null) {
            getString(R.string.voice_named, index + 1, accent)
        } else {
            getString(R.string.voice_named_gender, index + 1, accent, gender)
        }
    }

    private fun selectVoice(voice: Voice) {
        Prefs.setVoiceName(this, voice.name, profile)
        voiceRadios.forEach { (name, radio) -> radio.isChecked = name == voice.name }
        preview()
    }

    private fun preview() {
        val engine = tts ?: return
        if (!ttsReady) return
        // Same path the real announcements take, so what you hear is what you will get.
        // Preview what the selected profile actually says: the countdown speaks a bare number.
        val text = if (profile == SpeechProfile.COUNTDOWN) {
            TimeSpeech.number(Prefs.intervalMinutes(this) - 1)
        } else {
            TimeSpeech.phraseFor(LocalTime.now())
        }
        output.speak(engine, text, PREVIEW_UTTERANCE, profile)
    }

    private fun resetToDefaults() {
        Prefs.setVolumePercent(this, Prefs.DEFAULT_VOLUME_PERCENT, profile)
        Prefs.setPitchPercent(this, Prefs.DEFAULT_PITCH_PERCENT, profile)
        Prefs.setRatePercent(this, Prefs.DEFAULT_RATE_PERCENT, profile)

        loadProfileIntoControls()
        preview()
    }

    private fun updateVolumeLabel(percent: Int) {
        binding.volumeLabel.text = if (percent > 100) {
            getString(R.string.volume_label_boosted, percent)
        } else {
            getString(R.string.volume_label, percent)
        }
        binding.boostNote.visibility = if (percent > 100) View.VISIBLE else View.GONE
        binding.boostWarning.visibility = if (percent >= HARSH_BOOST_PERCENT) View.VISIBLE else View.GONE
    }

    private fun updateToneLabels() {
        binding.pitchLabel.text = getString(R.string.pitch_label, Prefs.pitchPercent(this, profile))
        binding.rateLabel.text = getString(R.string.rate_label, Prefs.ratePercent(this, profile))
    }

    override fun onDestroy() {
        output.release()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private companion object {
        const val PREVIEW_UTTERANCE = "settings-preview"
        const val PADDING_VERTICAL = 24

        /** One decimal place: the slider steps in half-seconds. */
        val SECONDS_FORMAT: java.text.DecimalFormat = java.text.DecimalFormat("0.0")

        /** Past this the limiter is working hard enough to be audible. */
        const val HARSH_BOOST_PERCENT = 250
    }
}
