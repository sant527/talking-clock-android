package com.example.timespeaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Lets a key press cut an announcement short.
 *
 * Only live while something is being said, so the keys behave normally the rest of the time —
 * the volume buttons should not stop adjusting volume just because the app is installed.
 *
 * Two routes, because Android gives apps no single "any key" hook:
 *
 * - **Volume keys** arrive through a [MediaSession] registered for remote volume. A session
 *   that owns volume gets the press instead of the system volume dialog, which is the only way
 *   to see those keys with the screen off.
 * - **The power key** cannot be intercepted at all; it is reserved by the system. But it always
 *   toggles the screen, so the screen on/off broadcast stands in for it.
 */
class Silencer(private val context: Context, private val onSilence: () -> Unit) {

    private var session: MediaSession? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    fun start() {
        if (session != null) return

        // Audio focus first. A media session only receives the volume keys while it is the
        // active one, and holding focus is what makes it active - without this the presses go
        // to the system volume dialog and never reach us.
        requestAudioFocus()

        session = MediaSession(context, SESSION_TAG).apply {
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, MAX_VOLUME, HALF) {
                override fun onAdjustVolume(direction: Int) = silence()
                override fun onSetVolumeTo(volume: Int) = silence()
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
                    .build()
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPause() = silence()
                override fun onStop() = silence()
                // Headset and bluetooth buttons land here too.
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    silence()
                    return true
                }
            })
            isActive = true
        }

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = silence()
        }.also {
            context.registerReceiver(
                it,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
            )
        }
    }

    private fun requestAudioFocus() {
        val manager = context.getSystemService(android.media.AudioManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = android.media.AudioFocusRequest
            .Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .build()
            .also { manager.requestAudioFocus(it) }
    }

    private fun abandonAudioFocus() {
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        focusRequest?.let { manager?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun stop() {
        abandonAudioFocus()
        session?.apply {
            isActive = false
            release()
        }
        session = null

        screenReceiver?.let {
            // Unregistering an already-gone receiver throws; the service may have been torn down.
            runCatching { context.unregisterReceiver(it) }
        }
        screenReceiver = null
    }

    private fun silence() {
        stop()
        onSilence()
    }

    private companion object {
        const val SESSION_TAG = "TimeSpeaker"
        const val MAX_VOLUME = 100
        const val HALF = 50
    }
}
