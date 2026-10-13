package local.pinger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * Listens for notifications from the watched apps. When one arrives, rings continuously
 * (looping ringtone + vibration) until acknowledged or RING_TIMEOUT_MS elapses.
 *
 * Needs notification access (Settings > Notifications > Notification access).
 */
class WatchService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var toneThread: Thread? = null
    @Volatile private var toneRunning = false

    private val timeout = Runnable { acknowledge() }

    private val ackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            acknowledge()
        }
    }

    override fun onListenerConnected() {
        instance = this
        createChannel()
        ContextCompat.registerReceiver(
            this,
            ackReceiver,
            IntentFilter(ACTION_ACK),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onListenerDisconnected() {
        acknowledge()
        instance = null
        runCatching { unregisterReceiver(ackReceiver) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val label = WATCHED[sbn.packageName] ?: return
        if (pending) return
        // Cooldown: stay quiet for a while after the last ring stopped (e.g. mid-conversation).
        if (lastRingEnded != 0L && SystemClock.elapsedRealtime() - lastRingEnded < cooldownMs(this)) return

        pending = true
        source = label
        postStatusNotification()
        startRinging()
        handler.postDelayed(timeout, RING_TIMEOUT_MS)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (!pending) return
        // Our own ping notification swiped away (Android 14+ lets users dismiss ongoing ones).
        if (sbn.packageName == packageName && sbn.id == PING_ID) {
            acknowledge()
            return
        }
        if (!WATCHED.containsKey(sbn.packageName)) return
        // User swiped / cleared / tapped a watched notification: that's an acknowledgement,
        // even if older notifications or a group summary from the same app are still showing.
        if (reason in USER_DISMISS_REASONS) {
            acknowledge()
            return
        }
        // Read elsewhere or cleared by the app itself: stop only if nothing watched is left.
        val stillWatched = activeNotifications?.any { WATCHED.containsKey(it.packageName) } ?: false
        if (!stillWatched) acknowledge()
    }

    private fun postStatusNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ackIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_ACK).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Check $source")
            .setContentText("You have a $source notification waiting.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true) // swipeable on Android 14+, so the delete intent also acknowledges
            .setContentIntent(openApp)
            .setDeleteIntent(ackIntent)
            .addAction(0, "Acknowledge", ackIntent)
            .build()
        nm.notify(PING_ID, notification)
    }

    /** Loops a ringtone (or the synthesized default ping) and a vibration pattern until stopRinging(). */
    private fun startRinging() {
        val savedUri = savedRingtoneUri(this)
        if (savedUri != null) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            runCatching {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@WatchService, savedUri)
                    isLooping = true
                    prepare()
                    start()
                }
            }
        } else {
            startDefaultTone()
        }

        val pattern = longArrayOf(0, 800, 400, 800, 400, 800, 1000)
        val v = getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createWaveform(pattern, 0)) // repeat from index 0: loops forever
        vibrator = v
    }

    /** Synthesizes a repeating series of pings alternating random-high then random-low pitch. */
    private fun startDefaultTone() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sampleRate = 44100
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(sampleRate * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        toneRunning = true
        track.play()
        toneThread = Thread {
            var high = true
            while (toneRunning) {
                val freq = if (high) Random.nextInt(1100, 1800) else Random.nextInt(350, 700)
                val ping = pingSamples(freq, durationMs = 180, sampleRate = sampleRate)
                if (!toneRunning) break
                runCatching { track.write(ping, 0, ping.size) }
                high = !high
                try {
                    Thread.sleep(130)
                } catch (e: InterruptedException) {
                    break
                }
            }
            runCatching { track.stop() }
            runCatching { track.release() }
        }.also { it.start() }
    }

    /** A short sine-wave ping at freqHz with a linear fade in/out to avoid clicks. */
    private fun pingSamples(freqHz: Int, durationMs: Int, sampleRate: Int): ShortArray {
        val n = sampleRate * durationMs / 1000
        val fade = (n * 0.1).toInt().coerceAtLeast(1)
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val envelope = minOf(1.0, i.toDouble() / fade, (n - i).toDouble() / fade)
            (Math.sin(2.0 * Math.PI * freqHz * t) * envelope * Short.MAX_VALUE * 0.8).toInt().toShort()
        }
    }

    private fun stopDefaultTone() {
        toneRunning = false
        toneThread?.interrupt()
        toneThread = null
    }

    private fun stopRinging() {
        mediaPlayer?.let { mp -> runCatching { mp.stop() }; runCatching { mp.release() } }
        mediaPlayer = null
        stopDefaultTone()
        vibrator?.cancel()
        vibrator = null
    }

    private fun createChannel() {
        // Respects Do Not Disturb (no setBypassDnd). Sound/vibration are driven manually
        // (startRinging/stopRinging) so the channel itself stays silent to avoid double-alerting.
        val channel = NotificationChannel(CHANNEL_ID, "Pings", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings until you acknowledge a Slack/Teams notification"
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopPinging() {
        handler.removeCallbacks(timeout)
        stopRinging()
        getSystemService(NotificationManager::class.java).cancel(PING_ID)
    }

    companion object {
        const val ACTION_ACK = "local.pinger.ACK"
        private const val CHANNEL_ID = "pings"
        private const val PING_ID = 1001

        const val PREFS = "pinger_prefs"
        const val KEY_RINGTONE_URI = "ringtone_uri"
        const val KEY_COOLDOWN_MIN = "cooldown_min"
        const val DEFAULT_COOLDOWN_MIN = 2f

        /** Minutes (fractions allowed) after a ring stops during which new notifications don't ring. */
        fun cooldownMinutes(context: Context): Float =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_COOLDOWN_MIN, DEFAULT_COOLDOWN_MIN)

        private fun cooldownMs(context: Context): Long = (cooldownMinutes(context) * 60_000).toLong()

        /** elapsedRealtime() when the last ring stopped; 0 if it never rang. */
        @Volatile private var lastRingEnded = 0L

        /** The user-picked ringtone, if any; null means ring with the synthesized default ping. */
        fun savedRingtoneUri(context: Context): Uri? {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RINGTONE_URI, null)
            return saved?.let { Uri.parse(it) }
        }

        /** Package name -> label. Verify package names on your phone if a notification is not caught. */
        val WATCHED = mapOf(
            "com.Slack" to "Slack",
            "com.microsoft.teams" to "Teams",
        )

        /** Stop ringing on its own after this long, even if never acknowledged. */
        const val RING_TIMEOUT_MS = 10 * 60_000L

        /** Removal reasons that mean the user dismissed or opened the notification. */
        private val USER_DISMISS_REASONS = setOf(
            NotificationListenerService.REASON_CANCEL,
            NotificationListenerService.REASON_CANCEL_ALL,
            NotificationListenerService.REASON_CLICK,
        )

        @Volatile var pending = false
            private set
        @Volatile var source = ""
            private set

        @Volatile private var instance: WatchService? = null

        /** Stops the ring. Safe to call when nothing is pending. */
        fun acknowledge() {
            if (pending) lastRingEnded = SystemClock.elapsedRealtime()
            pending = false
            instance?.stopPinging()
        }
    }
}
