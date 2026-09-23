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

    /** Utterances still to finish before the wake lock can go. */
    private var pendingUtterances = 0

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
        startForegroundWithNotification()

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

        // A quarter hour that is also an ordinary mark is announced once, as a major - saying it
        // twice over would be worse than either.
        if (Prefs.majorEnabled(this) && isMark(now, Prefs.majorIntervalMinutes(this))) {
            announceMajor(now)
            return
        }

        if (now.minute % interval == 0) {
            announce()
            return
        }
        if (!Prefs.countdownEnabled(this)) return

        // Vibrate-only is chosen to keep the phone quiet. A countdown that still spoke a number
        // every minute would defeat that entirely, and the countdown never vibrates — so in that
        // mode it simply says nothing.
        if (!Prefs.announcementFeedback(this).speaks) return

        // Half-minutes since the last mark, so a step of 2.5 minutes is exactly expressible.
        val intervalHalves = interval * 2
        val elapsed = halfOfDay(now) % intervalHalves
        if (elapsed == 0 || elapsed % Prefs.countdownStepHalves(this) != 0) return

        val remainingHalves = intervalHalves - elapsed
        if (ttsReady) {
            speakText(
                TimeSpeech.halfMinutes(remainingHalves),
                SpeechProfile.COUNTDOWN,
                Prefs.speakRepeats(this, SpeechProfile.COUNTDOWN)
            )
        } else {
            announcePending = true
        }
    }

    /**
     * Delivers an announcement as speech, a vibration, or both.
     *
     * Only announcements reach here — the countdown speaks through [speakText] directly and never
     * vibrates.
     */
    private fun speak(time: LocalTime) {
        val feedback = Prefs.announcementFeedback(this, SpeechProfile.MAIN)
        if (feedback.vibrates) vibrate(SpeechProfile.MAIN)
        if (feedback.speaks) speakText(TimeSpeech.phraseFor(time), SpeechProfile.MAIN, Prefs.speakRepeats(this, SpeechProfile.MAIN))
    }

    /** The quarter-hour announcement: its own voice, and the time repeated. */
    private fun announceMajor(time: LocalTime) {
        val feedback = Prefs.announcementFeedback(this, SpeechProfile.MAJOR)
        if (feedback.vibrates) vibrate(SpeechProfile.MAJOR)
        if (!feedback.speaks) return

        if (!ttsReady) {
            announcePending = true
            return
        }
        speakText(TimeSpeech.phraseFor(time), SpeechProfile.MAJOR, Prefs.speakRepeats(this, SpeechProfile.MAJOR))
    }

    private fun vibrate(profile: SpeechProfile) {
        Vibration.buzz(
            this,
            Prefs.vibrationMillis(this, profile).toLong(),
            Prefs.vibrationRepeats(this, profile)
        )
    }

    private fun speakText(text: String, profile: SpeechProfile, repeats: Int = 1) {
        val engine = tts ?: return
        if (!ttsReady) return

        // Keep the CPU alive for the utterances; with the screen off the device would otherwise
        // doze off mid-sentence. The timeout is a backstop in case onDone never arrives.
        acquireWakeLock()
        // Counted, so a repeated announcement does not release the wake lock after its first
        // utterance and fall asleep partway through.
        pendingUtterances = repeats.coerceAtLeast(1)
        output.speak(engine, text, UTTERANCE_ID, profile, repeats)
        boostUnsupported = output.boostUnsupported
    }

    private fun scheduleNextAnnouncement() {
        val interval = Prefs.intervalMinutes(this)
        // With the countdown on, something is spoken every minute, so the alarm has to fire every
        // minute too — the interval marks are a subset of those.
        val step = if (Prefs.countdownEnabled(this)) 1 else interval

        // Dated rather than time-of-day arithmetic, so the 23:55 mark rolls into 00:00 tomorrow
        // instead of scheduling an alarm nearly a day in the past.
        val now = LocalDateTime.now()
        var next: LocalDateTime? = if (Prefs.intervalEnabled(this)) nextMark(now, step) else null
        if (Prefs.majorEnabled(this)) {
            // Quarter hours are not necessarily interval marks - at an interval of 10, :15 and
            // :45 are not - so the alarm has to be pulled forward to catch them.
            val nextMajor = nextMark(now, Prefs.majorIntervalMinutes(this))
            if (next == null || nextMajor.isBefore(next)) next = nextMajor
        }

        if (Prefs.countdownEnabled(this) && Prefs.intervalEnabled(this)) {
            val point = nextCountdownPoint(now, interval, Prefs.countdownStepHalves(this))
            if (point != null && (next == null || point.isBefore(next))) next = point
        }

        val alarms = getSystemService(AlarmManager::class.java)
        if (next == null) {
            // Everything is switched off: cancel the pending alarm rather than waking for nothing.
            alarms.cancel(announcePendingIntent())
            notificationManager().notify(NOTIFICATION_ID, buildIdleNotification())
            return
        }
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // setExactAndAllowWhileIdle is the only scheduling call that survives Doze, which is
        // exactly the state the phone is in when it is locked on a desk.
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, announcePendingIntent())

        notificationManager().notify(NOTIFICATION_ID, buildNotification(nextSpokenMark(interval)))
    }

    /** The next time something is actually announced: an interval mark or a quarter hour. */
    private fun nextSpokenMark(interval: Int): LocalTime {
        val now = LocalTime.now()
        val ordinary = if (Prefs.intervalEnabled(this)) nextMark(now, interval) else null
        val major = if (Prefs.majorEnabled(this)) nextMark(now, Prefs.majorIntervalMinutes(this)) else null

        return when {
            ordinary != null && major != null -> if (major.isBefore(ordinary)) major else ordinary
            else -> ordinary ?: major ?: now
        }
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
        if (--pendingUtterances > 0) return
        output.release()
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun startForegroundWithNotification() {
        val notification = if (Prefs.anythingEnabled(this)) {
            buildNotification(nextSpokenMark(Prefs.intervalMinutes(this)))
        } else {
            buildIdleNotification()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Shown when every section is switched off, so the notification does not promise a time. */
    private fun buildIdleNotification(): Notification =
        baseNotification().setContentText(getString(R.string.status_nothing_enabled)).build()

    private fun buildNotification(next: LocalTime): Notification =
        baseNotification()
            .setContentText(getString(R.string.status_next, NOTIFICATION_FORMAT.format(next)))
            .build()

    private fun baseNotification(): NotificationCompat.Builder {
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
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
            from.plusMinutes(minutesToNextMark(minuteOfDay(from.hour, from.minute), interval))
                .withSecond(0).withNano(0)

        fun nextMark(from: LocalDateTime, interval: Int): LocalDateTime =
            from.plusMinutes(minutesToNextMark(minuteOfDay(from.hour, from.minute), interval))
                .withSecond(0).withNano(0)

        /**
         * Marks are counted from midnight, not from the top of the hour.
         *
         * Every interval offered divides a whole day, so this keeps them aligned; counting within
         * the hour would break anything longer than 60 minutes, where a two-hour interval would
         * match minute zero of every hour and fire twice as often as asked.
         */
        fun minuteOfDay(hour: Int, minute: Int): Int = hour * 60 + minute

        /** The same idea in half-minutes, which is the unit the countdown steps in. */
        fun halfOfDay(time: LocalDateTime): Int =
            (time.hour * 60 + time.minute) * 2 + if (time.second >= 30) 1 else 0

        fun halfOfDay(time: LocalTime): Int =
            (time.hour * 60 + time.minute) * 2 + if (time.second >= 30) 1 else 0

        /**
         * The next countdown point after [from], or null when the next thing due is the mark.
         *
         * Worked in half-minutes from the last mark rather than from midnight, because the
         * points restart at every mark and a step need not divide the interval.
         */
        fun nextCountdownPoint(from: LocalDateTime, interval: Int, stepHalves: Int): LocalDateTime? {
            val intervalHalves = interval * 2
            val elapsed = halfOfDay(from) % intervalHalves
            val offset = (elapsed / stepHalves + 1) * stepHalves
            if (offset >= intervalHalves) return null

            val boundary = from.withSecond(if (from.second >= 30) 30 else 0).withNano(0)
            return boundary.plusSeconds((offset - elapsed) * 30L)
        }

        fun isMark(time: LocalTime, interval: Int): Boolean =
            minuteOfDay(time.hour, time.minute) % interval == 0

        private fun minutesToNextMark(minuteOfDay: Int, interval: Int): Long =
            (interval - (minuteOfDay % interval)).toLong()

        private fun intentFor(context: Context, action: String?): Intent =
            Intent(context, TimeAnnouncerService::class.java).also { it.action = action }
    }
}
