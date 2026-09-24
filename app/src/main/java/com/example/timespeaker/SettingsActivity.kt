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
import com.example.timespeaker.databinding.ItemProfileBinding
import com.example.timespeaker.databinding.ItemVoiceBinding
import com.example.timespeaker.databinding.SectionStyleBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
    private val profileRadios = linkedMapOf<SpeechProfile, RadioButton>()
    private val profileSwitches = linkedMapOf<SpeechProfile, com.google.android.material.materialswitch.MaterialSwitch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredTheme()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        lockToPortrait()

        setUpIntervalPicker()
        bindStyleSection(binding.intervalStyle, SpeechProfile.MAIN)
        bindStyleSection(binding.countdownStyle, SpeechProfile.COUNTDOWN)
        bindStyleSection(binding.majorStyle, SpeechProfile.MAJOR)
        setUpCountdownSwitch()
        setUpCountdownStep()
        setUpMajorControls()
        // Reflect the stored switch positions on open; until now this only ran when a switch
        // was touched, so a section that was off still showed all its controls.
        updateIntervalHint()
        updateMajorHint()
        refreshSectionBodies()
        refreshProfileSwitcher()
        setUpThemePicker()
        setUpClockStylePicker()
        setUpCallsSwitch()

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
            updateIntervalHint()
            updateCountdownStepHint()
            // The alarm already in flight was set for the old interval; move it.
            TimeAnnouncerService.reschedule(this)
            refreshCountdownAvailability()
        }
    }

    /**
     * Wires one section's style block: speak/vibrate choice, vibration length and test.
     *
     * One function for all three sections, because they behave identically and only differ in
     * which profile they store under.
     */
    private fun bindStyleSection(section: SectionStyleBinding, profile: SpeechProfile) {
        section.styleGroup.check(styleButtonFor(section, Prefs.announcementFeedback(this, profile)))
        section.styleGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setAnnouncementFeedback(this, styleFor(section, checkedId), profile)
            refreshStyleSection(section, profile)
        }

        section.vibrationSlider.value = Prefs.vibrationMillis(this, profile).toFloat()
        section.vibrationSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setVibrationMillis(this, value.toInt(), profile)
            updateVibrationLabel(section, profile)
        }
        section.vibrationSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            // Feeling the length you just chose is the only way to judge it.
            override fun onStopTrackingTouch(slider: Slider) = previewVibration(section, profile)
        })

        section.speakRepeatsSlider.value = Prefs.speakRepeats(this, profile).toFloat()
        section.speakRepeatsSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setSpeakRepeats(this, value.toInt(), profile)
            updateRepeatLabels(section, profile)
        }

        section.vibrationRepeatsSlider.value = Prefs.vibrationRepeats(this, profile).toFloat()
        section.vibrationRepeatsSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setVibrationRepeats(this, value.toInt(), profile)
            updateRepeatLabels(section, profile)
        }
        section.vibrationRepeatsSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = previewVibration(section, profile)
        })

        section.vibrationTestButton.setOnClickListener { previewVibration(section, profile) }

        refreshStyleSection(section, profile)
    }

    private fun styleButtonFor(section: SectionStyleBinding, feedback: AnnouncementFeedback): Int =
        when (feedback) {
            AnnouncementFeedback.SPEAK -> section.styleSpeak.id
            AnnouncementFeedback.SPEAK_AND_VIBRATE -> section.styleBoth.id
            AnnouncementFeedback.VIBRATE_ONLY -> section.styleVibrate.id
        }

    private fun styleFor(section: SectionStyleBinding, buttonId: Int): AnnouncementFeedback =
        when (buttonId) {
            section.styleBoth.id -> AnnouncementFeedback.SPEAK_AND_VIBRATE
            section.styleVibrate.id -> AnnouncementFeedback.VIBRATE_ONLY
            else -> AnnouncementFeedback.SPEAK
        }

    /** The length control only means anything when that section actually vibrates. */
    /**
     * All three counts stay on screen whatever the style is.
     *
     * They were hidden when the style did not use them, but a control that appears and vanishes
     * as you change a radio button above it is harder to find than one that is simply always
     * there. They take effect only when the style calls for them.
     */
    private fun refreshStyleSection(section: SectionStyleBinding, profile: SpeechProfile) {
        section.speakRepeatsLabel.visibility = View.VISIBLE
        section.speakRepeatsSlider.visibility = View.VISIBLE
        section.vibrationLabel.visibility = View.VISIBLE
        section.vibrationSlider.visibility = View.VISIBLE
        section.vibrationRepeatsLabel.visibility = View.VISIBLE
        section.vibrationRepeatsSlider.visibility = View.VISIBLE
        section.vibrationTestButton.visibility = View.VISIBLE

        updateVibrationLabel(section, profile)
        updateRepeatLabels(section, profile)
    }

    private fun updateRepeatLabels(section: SectionStyleBinding, profile: SpeechProfile) {
        val says = Prefs.speakRepeats(this, profile)
        section.speakRepeatsLabel.text =
            resources.getQuantityString(R.plurals.speak_repeats_label, says, says)

        val buzzes = Prefs.vibrationRepeats(this, profile)
        section.vibrationRepeatsLabel.text =
            resources.getQuantityString(R.plurals.vibration_repeats_label, buzzes, buzzes)
    }

    private fun updateVibrationLabel(section: SectionStyleBinding, profile: SpeechProfile) {
        val seconds = Prefs.vibrationMillis(this, profile) / 1000f
        section.vibrationLabel.text =
            getString(R.string.vibration_label, SECONDS_FORMAT.format(seconds))
    }

    /**
     * Buzzes for the chosen length, and says so while it runs.
     *
     * At the long end of the slider a silent button would look broken - ten seconds is long
     * enough that you need telling something is happening.
     */
    private fun previewVibration(section: SectionStyleBinding, profile: SpeechProfile) {
        val millis = Prefs.vibrationMillis(this, profile).toLong()
        val times = Prefs.vibrationRepeats(this, profile)
        Vibration.buzz(this, millis, times)
        // The button stays busy for the whole pattern, gaps included.
        val total = millis * times + GAP_MS * (times - 1)

        section.vibrationTestButton.setText(R.string.vibration_test_running)
        section.vibrationTestButton.isEnabled = false
        section.vibrationTestButton.postDelayed({
            section.vibrationTestButton.setText(R.string.vibration_test)
            section.vibrationTestButton.isEnabled = true
        }, total)
    }

    /** The major marks, spelled out the same way as the ordinary interval's. */
    private fun updateMajorHint() {
        binding.majorHint.text =
            getString(R.string.major_hint, clockExamples(Prefs.majorIntervalMinutes(this)))
    }

    /**
     * Spells out that the interval is anchored to the clock.
     *
     * "Every 5 minutes" reads as five minutes from whenever you switched it on; naming the actual
     * marks is the only way to make clear that it lands on 7:00, 7:05, 7:10 regardless.
     */
    private fun updateIntervalHint() {
        binding.intervalHint.text =
            getString(R.string.interval_hint, clockExamples(Prefs.intervalMinutes(this)))
    }

    /**
     * Three real marks, e.g. "7:00, 7:15, 7:30" — or "8:00, 10:00, 12:00" at two hours.
     *
     * Started from the first genuine mark at or after 7am rather than from 7am itself, because
     * a two-hour interval does not land on odd hours and the examples would be wrong.
     */
    private fun clockExamples(minutes: Int): String {
        val morning = 7 * 60
        val remainder = morning % minutes
        val firstMark = if (remainder == 0) morning else morning + (minutes - remainder)

        return (0..2).joinToString(", ") { step ->
            val mark = (firstMark + step * minutes) % (24 * 60)
            EXAMPLE_FORMAT.format(LocalTime.of(mark / 60, mark % 60))
        }
    }

    /**
     * Every option stays on screen whether its announcement is switched on or not.
     *
     * Collapsing a switched-off section hid the very settings you would want to look at before
     * deciding to switch it on.
     */
    private fun refreshSectionBodies() {
        binding.intervalBody.visibility = View.VISIBLE
        binding.countdownBody.visibility = View.VISIBLE
        binding.majorBody.visibility = View.VISIBLE
    }

    private fun setUpCountdownSwitch() {

        refreshCountdownAvailability()
    }

    private fun setUpCountdownStep() {
        Prefs.COUNTDOWN_STEP_CHOICES.forEach { halves ->
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                id = View.generateViewId()
                text = STEP_FORMAT.format(halves / 2f)
                minWidth = 0
                minimumWidth = 0
                tag = halves
            }
            binding.countdownRangeGroup.addView(button)
            if (halves == Prefs.countdownStepHalves(this)) binding.countdownRangeGroup.check(button.id)
        }

        binding.countdownRangeGroup.addOnButtonCheckedListener { group, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val halves = group.findViewById<MaterialButton>(checkedId)?.tag as? Int
                ?: return@addOnButtonCheckedListener
            Prefs.setCountdownStepHalves(this, halves)
            TimeAnnouncerService.reschedule(this)
            updateCountdownStepHint()
        }

        updateCountdownStepHint()
    }

    /**
     * Names the minutes it will actually speak on.
     *
     * The number spoken is always the minutes remaining; this setting only changes how often it
     * says one. Spelling out "7:02, 7:04" is the quickest way to show that.
     */
    private fun updateCountdownStepHint() {
        val intervalHalves = Prefs.intervalMinutes(this) * 2
        val step = Prefs.countdownStepHalves(this)

        val marks = generateSequence(step) { it + step }
            .takeWhile { it < intervalHalves }
            .map { POINT_FORMAT.format(LocalTime.of(7, 0).plusSeconds(it * 30L)) }
            .toList()

        binding.countdownRangeHint.text = when {
            marks.isEmpty() -> getString(R.string.countdown_step_none)
            marks.size <= 4 -> getString(R.string.countdown_range_hint, marks.joinToString(", "))
            else -> getString(
                R.string.countdown_range_hint,
                marks.take(4).joinToString(", ") + " …"
            )
        }
    }

    private fun setUpMajorControls() {
        Prefs.MAJOR_INTERVAL_CHOICES.forEach { minutes ->
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                id = View.generateViewId()
                text = minutes.toString()
                minWidth = 0
                minimumWidth = 0
                tag = minutes
            }
            binding.majorIntervalGroup.addView(button)
            if (minutes == Prefs.majorIntervalMinutes(this)) {
                binding.majorIntervalGroup.check(button.id)
            }
        }

        binding.majorIntervalGroup.addOnButtonCheckedListener { group, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val minutes = group.findViewById<MaterialButton>(checkedId)?.tag as? Int
                ?: return@addOnButtonCheckedListener
            Prefs.setMajorIntervalMinutes(this, minutes)
            // Major marks are not interval marks, so the pending alarm has to move with them.
            TimeAnnouncerService.reschedule(this)
            updateMajorHint()
        }


    }

    /**
     * Rebuilds the "Editing" list from the voices actually in play.
     *
     * Offering a Countdown tab while the countdown shares the main voice, or a Major tab while
     * major announcements are off, would be offering settings that change nothing.
     */
    /**
     * Builds the selector once, with every announcement listed.
     *
     * All three are always offered, unlike before: the selector is now how you reach a
     * switched-off announcement in order to switch it on.
     */
    private fun refreshProfileSwitcher() {
        binding.profileGroup.removeAllViews()
        profileRadios.clear()

        SpeechProfile.entries.forEach { candidate ->
            val row = ItemProfileBinding.inflate(layoutInflater, binding.profileGroup, false)

            row.profileRadio.text = getString(candidate.labelRes)
            row.profileRadio.isChecked = candidate == profile
            row.profileRadio.setOnClickListener { selectProfile(candidate) }
            profileRadios[candidate] = row.profileRadio

            // The switch sits beside the radio so an announcement can be turned on or off
            // without first selecting it and scrolling to its section.
            row.profileEnabled.isChecked = isEnabled(candidate)
            row.profileEnabled.setOnCheckedChangeListener { _, enabled ->
                setEnabled(candidate, enabled)
                TimeAnnouncerService.reschedule(this)
                refreshCountdownAvailability()
                refreshSelectedSection()
            }
            profileSwitches[candidate] = row.profileEnabled

            binding.profileGroup.addView(row.root)
        }

        refreshCountdownAvailability()
        refreshSelectedSection()
    }

    private fun isEnabled(which: SpeechProfile): Boolean = when (which) {
        SpeechProfile.MAIN -> Prefs.intervalEnabled(this)
        SpeechProfile.COUNTDOWN -> Prefs.countdownEnabled(this)
        SpeechProfile.MAJOR -> Prefs.majorEnabled(this)
    }

    private fun setEnabled(which: SpeechProfile, enabled: Boolean) = when (which) {
        SpeechProfile.MAIN -> Prefs.setIntervalEnabled(this, enabled)
        SpeechProfile.COUNTDOWN -> Prefs.setCountdownEnabled(this, enabled)
        SpeechProfile.MAJOR -> Prefs.setMajorEnabled(this, enabled)
    }

    private fun selectProfile(which: SpeechProfile) {
        if (profile == which) return
        profile = which
        profileRadios.forEach { (candidate, radio) -> radio.isChecked = candidate == which }
        refreshSelectedSection()
        loadProfileIntoControls()
        // Deliberately no scroll: switching announcements should leave you where you were,
        // not throw you back to the top of the screen.
        if (ttsReady) renderVoices()
    }

    /**
     * Shows only the selected announcement's settings.
     *
     * The voice controls below are hidden for the countdown while it borrows the main voice —
     * editing a voice that is not in use would be editing nothing.
     */
    private fun refreshSelectedSection() {
        binding.intervalSection.visibility = visibleIf(profile == SpeechProfile.MAIN)
        binding.countdownSection.visibility = visibleIf(profile == SpeechProfile.COUNTDOWN)
        binding.majorSection.visibility = visibleIf(profile == SpeechProfile.MAJOR)

        // Every announcement has a voice of its own, so the controls always apply.
        binding.voiceBlock.visibility = View.VISIBLE
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE

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
        val intervalOn = Prefs.intervalEnabled(this)
        val usable = intervalOn && Prefs.intervalMinutes(this) > 1
        profileSwitches[SpeechProfile.COUNTDOWN]?.isEnabled = usable
        binding.countdownExplainer.setText(
            when {
                // It counts down to the next interval mark, so without those there is no target.
                !intervalOn -> R.string.countdown_needs_interval_off
                !usable -> R.string.countdown_needs_interval
                else -> R.string.countdown_explainer
            }
        )

        // The voice switch and the profile tabs are only meaningful while a countdown is running
        // with settings of its own.
        val counting = usable && Prefs.countdownEnabled(this)
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

    private fun setUpCallsSwitch() {
        binding.callsSwitch.isChecked = Prefs.speakDuringCalls(this)
        binding.callsSwitch.setOnCheckedChangeListener { _, speak ->
            Prefs.setSpeakDuringCalls(this, speak)
        }
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
        // Preview what the selected profile actually does: the countdown speaks a bare number,
        // and a major announcement repeats.
        val text = if (profile == SpeechProfile.COUNTDOWN) {
            TimeSpeech.number(Prefs.intervalMinutes(this) - 1)
        } else {
            TimeSpeech.phraseFor(LocalTime.now())
        }
        val repeats = Prefs.speakRepeats(this, profile)
        output.speak(engine, text, PREVIEW_UTTERANCE, profile, repeats)
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

        /** Matches the pause Vibration puts between buzzes. */
        const val GAP_MS = 350L

        /** One decimal place: the slider steps in half-seconds. */
        val SECONDS_FORMAT: java.text.DecimalFormat = java.text.DecimalFormat("0.0")

        /** Example marks in the interval hint. */
        val EXAMPLE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.US)

        /** Countdown points can fall on a half minute, so they need the seconds shown. */
        val POINT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm:ss", Locale.US)

        /** "1", "1.5", "2" - no trailing zero on the whole numbers. */
        val STEP_FORMAT: java.text.DecimalFormat = java.text.DecimalFormat("0.#")

        /** Past this the limiter is working hard enough to be audible. */
        const val HARSH_BOOST_PERCENT = 250
    }
}
