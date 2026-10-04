package com.example.timespeaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File

/**
 * The single audio path for every announcement, whether it comes from the service or from a
 * preview on the settings screen.
 *
 * Shared deliberately: when the preview and the real announcement build their own speech
 * parameters, the preview stops being a preview.
 */
class SpeechOutput(private val context: Context) {

    private val boosts = mutableListOf<LoudnessEnhancer>()

    /** True once [LoudnessEnhancer] has turned out to be unavailable on this device. */
    var boostUnsupported: Boolean = false
        private set

    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Playback aimed at particular devices, see [speakToDevices]. */
    private val players = mutableListOf<MediaPlayer>()
    private var queued: Queued? = null

    /** One device to play on, at its own volume. */
    private class Target(val device: AudioDeviceInfo, val percent: Int)

    private class Queued(
        val synthesisId: String,
        val file: File,
        val targets: List<Target>,
        val repeats: Int
    )

    /**
     * False when nothing would be heard: Bluetooth-only is chosen and no Bluetooth device is
     * connected. Callers check this first, so a skipped announcement takes no wake lock.
     */
    fun canSpeak(): Boolean =
        Prefs.soundOutput(context) != SoundOutput.BLUETOOTH_ONLY || bluetoothConnected()

    /**
     * The listener to install on the engine, wrapping [onDone].
     *
     * Speaker-only speech is first rendered to a file, and that rendering finishing is not the
     * announcement finishing - so it is intercepted here, and [onDone] is called as each
     * repetition actually finishes playing.
     */
    fun listener(onDone: () -> Unit): UtteranceProgressListener {
        doneCallback = onDone
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId, failed = false)

            @Deprecated("Superseded by onError(String, Int)", ReplaceWith(""))
            override fun onError(utteranceId: String?) = finished(utteranceId, failed = true)
            override fun onError(utteranceId: String?, errorCode: Int) =
                finished(utteranceId, failed = true)
        }
    }

    private var doneCallback: () -> Unit = {}

    private fun finished(utteranceId: String?, failed: Boolean) {
        if (utteranceId?.endsWith(SYNTHESIS_SUFFIX) != true) {
            doneCallback()
            return
        }
        main.post {
            val job = queued?.takeIf { it.synthesisId == utteranceId } ?: return@post
            queued = null
            if (failed || !play(job)) repeat(job.repeats) { doneCallback() }
        }
    }

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
        release()

        val output = Prefs.soundOutput(context)
        val speakerPercent = Prefs.volumePercent(context, profile)
        val bluetooth = bluetooth()
        val bluetoothPercent = if (Prefs.separateBluetoothVolume(context, profile)) {
            Prefs.bluetoothVolumePercent(context, profile)
        } else {
            speakerPercent
        }

        // Playing on a chosen device, or on two at different volumes, needs a player of our own.
        val speaker = speaker()
        val targets = when {
            speaker == null -> emptyList()
            output == SoundOutput.SPEAKER_ONLY -> listOf(Target(speaker, speakerPercent))
            output == SoundOutput.BOTH && bluetooth != null && bluetoothPercent != speakerPercent ->
                listOf(Target(speaker, speakerPercent), Target(bluetooth, bluetoothPercent))
            else -> emptyList()
        }
        if (targets.isNotEmpty() && speakToDevices(engine, text, utteranceId, targets, repeats)) return

        // Otherwise Android's own routing decides, which is Bluetooth whenever it is connected -
        // except for speaker-only, which only lands here when it could not be honoured.
        val onBluetooth = bluetooth != null &&
            output != SoundOutput.SPEAKER_ONLY && output != SoundOutput.BOTH
        val percent = if (onBluetooth) bluetoothPercent else speakerPercent
        val volume = percent.coerceAtMost(100) / 100f

        // Announcements play on their own audio session so the amplifier can be attached to them
        // alone, leaving music and everything else on the device untouched.
        val sessionId = audio.generateAudioSessionId()
        applyBoost(sessionId, percent)

        // KEY_PARAM_VOLUME scales the utterance against the device's volume rather than
        // replacing it. It saturates at 1.0; anything above that is the amplifier's job.
        //
        // The alarm stream is what gets both: Android plays alarms on the speaker and on a
        // connected headset together, so they are heard even with the headphones off.
        val stream = if (output == SoundOutput.BOTH) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
            putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, sessionId)
        }
        repeat(repeats.coerceAtLeast(1)) { index ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(text, mode, params, "$utteranceId-$index")
        }
    }

    /**
     * Speech on particular devices: the phone speaker while Bluetooth is connected, or speaker
     * and Bluetooth each at its own volume.
     *
     * The speech engine plays wherever Android routes it and cannot be pointed at a device, so
     * the words are rendered to a file and played by a [MediaPlayer] per device. Returns false
     * when that is not possible, and the caller falls back to ordinary speech.
     */
    private fun speakToDevices(
        engine: TextToSpeech,
        text: String,
        utteranceId: String,
        targets: List<Target>,
        repeats: Int
    ): Boolean {
        // MediaPlayer only accepts a preferred device from Android 9.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false

        val synthesisId = "$utteranceId$SYNTHESIS_SUFFIX"
        val file = File(context.cacheDir, SPEECH_FILE)
        queued = Queued(synthesisId, file, targets, repeats.coerceAtLeast(1))
        val result = engine.synthesizeToFile(text, Bundle(), file, synthesisId)
        if (result != TextToSpeech.SUCCESS) {
            queued = null
            return false
        }
        return true
    }

    private fun play(job: Queued): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false

        val started = job.targets.mapIndexedNotNull { index, target ->
            // Every copy repeats, but only the first reports progress - the copies play the same
            // file side by side, and counting each would end the announcement early.
            playOn(job, target, reportsDone = index == 0)
        }
        players += started
        // Some copies may have failed to start; if the reporting one did not, nothing would
        // ever say the announcement is over.
        if (started.size < job.targets.size) {
            release()
            return false
        }
        started.forEach { it.start() }
        return true
    }

    private fun playOn(job: Queued, target: Target, reportsDone: Boolean): MediaPlayer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        var left = job.repeats
        val volume = target.percent.coerceAtMost(100) / 100f
        val sessionId = audio.generateAudioSessionId()
        applyBoost(sessionId, target.percent)

        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // Its own session, so each copy gets its own amplifier.
                audioSessionId = sessionId
                setDataSource(job.file.path)
                setPreferredDevice(target.device)
                setVolume(volume, volume)
                setOnCompletionListener {
                    left--
                    if (left > 0) it.start()
                    if (reportsDone) doneCallback()
                }
                prepare()
            }
        } catch (error: Exception) {
            null
        }
    }

    private fun speaker(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun bluetoothConnected(): Boolean = bluetooth() != null

    /** Media-capable Bluetooth only: a headset connected just for calls does not play speech. */
    private fun bluetooth(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }

    /**
     * Attaches a [LoudnessEnhancer] for volumes above 100%.
     *
     * The speech engine cannot be told to play louder than the device's media volume, so extra
     * loudness has to come from amplifying the output. LoudnessEnhancer applies make-up gain with
     * built-in limiting, which keeps a shouted announcement from clipping.
     */
    private fun applyBoost(sessionId: Int, percent: Int) {
        if (percent <= 100) return

        val gainMillibels = ((percent - 100) / BOOST_RANGE_PERCENT * MAX_BOOST_MILLIBELS).toInt()
        try {
            boosts += LoudnessEnhancer(sessionId).apply {
                setTargetGain(gainMillibels)
                enabled = true
            }
        } catch (error: RuntimeException) {
            // Some devices and ROMs ship without the effect. Falling back to unboosted audio is
            // better than dropping the announcement.
            boostUnsupported = true
        }
    }

    fun release() {
        boosts.forEach { it.release() }
        boosts.clear()
        queued = null
        val stopping = players.toList()
        players.clear()
        main.post { stopping.forEach { it.release() } }
    }

    private companion object {
        /** Gain applied at the 400% end of the slider, in millibels (+40 dB). */
        const val MAX_BOOST_MILLIBELS = 4000f

        /** Slider span over which that gain is applied: 100% to 400%. */
        const val BOOST_RANGE_PERCENT = 300f

        /** Marks the rendering step of speaker-only speech, so it is not taken for the end. */
        const val SYNTHESIS_SUFFIX = "-render"

        const val SPEECH_FILE = "announcement.wav"
    }
}
