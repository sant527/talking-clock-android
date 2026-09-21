package com.example.timespeaker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Speaks the time on every five-minute mark, including while the screen is off.
 *
 * Announcements are driven by exact alarms rather than a ticking loop holding a permanent wake
 * lock: the device is allowed to sleep between marks and is woken only for the two seconds it
 * takes to talk, which is the difference between a negligible and a very visible battery cost.
 */
class TimeAnnouncerService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Set when an announcement is due before the speech engine has finished starting up. */
    private var announcePending = false

    /** Announcing once on start is what tells the user the app is actually working. */
    private var greeted = false

    private var wakeLock: PowerManager.WakeLock? = null

    private val output by lazy { SpeechOutput(this) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ttsFailed = false
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to the foreground before anything else: Android gives a started service only a
        // few seconds to do this before killing it.
        startForegroundWithNotification(nextMark(LocalTime.now(), Prefs.intervalMinutes(this)))

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SPEAK_NOW -> speak(LocalTime.now())

            ACTION_ANNOUNCE -> {
                announceDue()
                scheduleNextAnnouncement()
            }

            ACTION_RESCHEDULE -> scheduleNextAnnouncement()

            else -> {
                if (!greeted) {
                    greeted = true
                    announce()
                }
                scheduleNextAnnouncement()
            }
        }

        // START_STICKY: if the system reclaims the process under memory pressure, bring it back.
        return START_STICKY
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ttsFailed = true
            return
        }

        val result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallback = engine.setLanguage(Locale.US)
            if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsFailed = true
                return
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finishUtterance()

            @Deprecated("Superseded by onError(String, Int)", ReplaceWith(""))
            override fun onError(utteranceId: String?) = finishUtterance()
            override fun onError(utteranceId: String?, errorCode: Int) = finishUtterance()
        })

        ttsReady = true
        if (announcePending) {
            announcePending = false
            speak(LocalTime.now())
        }
    }

    /** Speaks now, or as soon as the engine reports itself ready. */
    private fun announce() {
        if (ttsReady) speak(LocalTime.now()) else announcePending = true
    }

    /**
     * What is due right now: the full time on an interval mark, otherwise — when the countdown is
     * switched on — the number of minutes left until the next one.
     */
    private fun announceDue() {
        val now = LocalTime.now()
        val interval = Prefs.intervalMinutes(this)

        if (now.minute % interval == 0) {
            announce()
            return
        }
        if (!Prefs.countdownEnabled(this)) return

        val remaining = interval - (now.minute % interval)
        if (ttsReady) speakText(TimeSpeech.number(remaining), SpeechProfile.COUNTDOWN) else announcePending = true
    }

    private fun speak(time: LocalTime) = speakText(TimeSpeech.phraseFor(time), SpeechProfile.MAIN)

    private fun speakText(text: String, profile: SpeechProfile) {
        val engine = tts ?: return
        if (!ttsReady) return

        // Keep the CPU alive for the utterance; with the screen off the device would otherwise
        // doze off mid-sentence. The timeout is a backstop in case onDone never arrives.
        acquireWakeLock()
        output.speak(engine, text, UTTERANCE_ID, profile)
        boostUnsupported = output.boostUnsupported
    }

    private fun scheduleNextAnnouncement() {
        val interval = Prefs.intervalMinutes(this)
        // With the countdown on, something is spoken every minute, so the alarm has to fire every
        // minute too — the interval marks are a subset of those.
        val step = if (Prefs.countdownEnabled(this)) 1 else interval

        // Dated rather than time-of-day arithmetic, so the 23:55 mark rolls into 00:00 tomorrow
        // instead of scheduling an alarm nearly a day in the past.
        val next = nextMark(LocalDateTime.now(), step)
        val alarms = getSystemService(AlarmManager::class.java)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // setExactAndAllowWhileIdle is the only scheduling call that survives Doze, which is
        // exactly the state the phone is in when it is locked on a desk.
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, announcePendingIntent())

        notificationManager().notify(NOTIFICATION_ID, buildNotification(nextMark(LocalTime.now(), interval)))
    }

    private fun announcePendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_ANNOUNCE,
        Intent(this, TimeAnnouncerService::class.java).setAction(ACTION_ANNOUNCE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun finishUtterance() {
        output.release()
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun startForegroundWithNotification(next: LocalTime) {
        val notification = buildNotification(next)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(next: LocalTime): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, TimeAnnouncerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.status_next, NOTIFICATION_FORMAT.format(next)))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        getSystemService(AlarmManager::class.java).cancel(announcePendingIntent())
        output.release()
        releaseWakeLock()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_ANNOUNCE = "com.example.timespeaker.ANNOUNCE"
        private const val ACTION_STOP = "com.example.timespeaker.STOP"
        private const val ACTION_SPEAK_NOW = "com.example.timespeaker.SPEAK_NOW"
        private const val ACTION_RESCHEDULE = "com.example.timespeaker.RESCHEDULE"

        private const val CHANNEL_ID = "announcements"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_ANNOUNCE = 100
        private const val REQUEST_STOP = 101
        private const val REQUEST_OPEN = 102

        private const val UTTERANCE_ID = "time-announcement"
        private const val WAKE_LOCK_TAG = "TimeSpeaker:announcement"
        private const val WAKE_LOCK_TIMEOUT_MS = 15_000L

        private val NOTIFICATION_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        /** Read by the UI to label its controls; written only from the service's main thread. */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var ttsFailed: Boolean = false
            private set

        /** True once a device has turned out to have no loudness amplifier available. */
        @Volatile
        var boostUnsupported: Boolean = false
            internal set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intentFor(context, null))
        }

        fun stop(context: Context) {
            context.startService(intentFor(context, ACTION_STOP))
        }

        fun speakNow(context: Context) {
            context.startService(intentFor(context, ACTION_SPEAK_NOW))
        }

        /**
         * Recomputes the pending alarm after the interval or countdown setting changes.
         *
         * Without this a change from hourly to every-five-minutes would not take effect until the
         * alarm already in flight fired, up to an hour later. Does nothing when announcements are
         * off, so changing a setting never switches them back on.
         */
        fun reschedule(context: Context) {
            if (!isRunning) return
            context.startService(intentFor(context, ACTION_RESCHEDULE))
        }

        /** The next wall-clock mark strictly after [from], for an [interval]-minute step. */
        fun nextMark(from: LocalTime, interval: Int): LocalTime =
            from.plusMinutes(minutesToNextMark(from.minute, interval)).withSecond(0).withNano(0)

        fun nextMark(from: LocalDateTime, interval: Int): LocalDateTime =
            from.plusMinutes(minutesToNextMark(from.minute, interval)).withSecond(0).withNano(0)

        private fun minutesToNextMark(minute: Int, interval: Int): Long =
            (interval - (minute % interval)).toLong()

        private fun intentFor(context: Context, action: String?): Intent =
            Intent(context, TimeAnnouncerService::class.java).also { it.action = action }
    }
}
