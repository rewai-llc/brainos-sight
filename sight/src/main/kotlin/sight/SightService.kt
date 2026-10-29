package sight

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.brainos.sight.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** What the screen shows. */
data class SightState(
    val running: Boolean = false,
    val camera: String = "off",
    val status: String = "Not started",
    val busy: Boolean = false,
    val lastSpoken: String = "",
    val photo: Bitmap? = null,
    val model: String = "",
    val importing: Int = -1, // model copy progress 0..100, -1 = not copying
)

object SightHub {
    val state = MutableStateFlow(SightState())
    val flow: StateFlow<SightState> get() = state

    fun command(context: Context, action: String) {
        val i = Intent(context, SightService::class.java).setAction(action)
        if (action == SightService.ACTION_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
    }
}

class SightSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sight", Context.MODE_PRIVATE)
    var rate: Float
        get() = prefs.getFloat("rate", 1.0f)
        set(v) = prefs.edit().putFloat("rate", v).apply()
    var flip: Boolean
        get() = prefs.getBoolean("flip", false)
        set(v) = prefs.edit().putBoolean("flip", v).apply()
}

/**
 * Keeps the camera connected and answers requests while the screen is off.
 * Headset button: one press = read, two quick presses = describe.
 * Earbud tap (play/pause): ask a spoken question. Tapping while it talks stops it.
 */
class SightService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var settings: SightSettings
    private lateinit var speaker: Speaker
    private lateinit var eyes: Eyes
    private lateinit var ears: Ears
    private var camera: CameraLink? = null
    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var busy = false
    private var pendingPress: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SightSettings(this)
        speaker = Speaker(this).also { it.setRate(settings.rate) }
        eyes = Eyes(this)
        ears = Ears(this)
        refreshModelState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stopAll()
            ACTION_READ -> request(Request.Read)
            ACTION_DESCRIBE -> request(Request.Describe)
            ACTION_ASK -> ask()
            ACTION_HUSH -> hush()
            ACTION_SETTINGS -> applySettings()
            ACTION_MODEL -> refreshModelState()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (SightHub.state.value.running) return
        if (!goForeground()) {
            stopSelf()
            return
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "brainos:sight").apply { setReferenceCounted(false); acquire() }
        camera = CameraLink(
            this,
            onStatus = { s -> onCameraStatus(s) },
            onButton = { main.post { onHeadsetButton() } },
        ).also { it.start() }
        startMediaSession()
        SightHub.state.update { it.copy(running = true, status = "Ready") }
        speaker.say("BrainOS Sight is on.")
    }

    private fun stopAll() {
        ears.cancel()
        speaker.stop()
        camera?.stop()
        camera = null
        session?.release()
        session = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        SightHub.state.update { it.copy(running = false, camera = "off", status = "Stopped", busy = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        speaker.shutdown()
        worker.execute { eyes.close() }
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ controls

    private fun onCameraStatus(s: String) {
        val prev = SightHub.state.value.camera
        SightHub.state.update { it.copy(camera = s) }
        if (s == "connected" && prev != "connected") {
            camera?.setOrientation(settings.flip, settings.flip)
            speaker.add("Camera connected.")
        } else if (prev == "connected" && s != "connected" && SightHub.state.value.running) {
            speaker.add("Camera disconnected.")
        }
    }

    /** One press reads; a second press within 0.6 s describes instead. A press while talking stops it. */
    private fun onHeadsetButton() {
        val pending = pendingPress
        if (pending != null) {
            main.removeCallbacks(pending)
            pendingPress = null
            request(Request.Describe)
            return
        }
        if (speaker.isSpeaking && !busy) {
            hush()
            return
        }
        val r = Runnable {
            pendingPress = null
            request(Request.Read)
        }
        pendingPress = r
        main.postDelayed(r, 600)
    }

    private fun hush() {
        ears.cancel()
        speaker.stop()
    }

    private fun ask() {
        if (busy) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO) || !ears.available) {
            speaker.say("Speech recognition isn't available.")
            return
        }
        speaker.say("Ask.") {
            main.post {
                SightHub.state.update { it.copy(status = "Listening") }
                ears.listen { heard ->
                    val req = heard?.let { Requests.parse(it) }
                    if (req == null) {
                        SightHub.state.update { it.copy(status = "Ready") }
                        speaker.say("I didn't catch that.")
                    } else {
                        request(req)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the work

    private fun request(req: Request) {
        if (busy) return
        val cam = camera
        if (cam == null || !cam.isReady) {
            speaker.say(if (cam == null) "Sight is off." else "The camera isn't connected.")
            return
        }
        busy = true
        ears.cancel()
        SightHub.state.update { it.copy(busy = true, status = "Looking") }
        speaker.say("Looking.")
        worker.execute {
            val reply = try {
                work(cam, req)
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "request-failed " + t.javaClass.simpleName + ": " + t.message)
                "Something went wrong."
            }
            main.post {
                busy = false
                SightHub.state.update { it.copy(busy = false, status = "Ready", lastSpoken = reply) }
                speaker.say(reply)
            }
        }
    }

    /** Runs on the worker thread. Returns what to say. */
    private fun work(cam: CameraLink, req: Request): String {
        val jpeg = cam.snap() ?: return "The camera didn't send a photo. Try again."
        val bitmap = eyes.decode(jpeg) ?: return "The photo couldn't be opened."
        SightHub.state.update { it.copy(photo = bitmap, status = if (req is Request.Read) "Reading" else "Thinking") }
        val read = eyes.read(bitmap)
        val dark = if (read.dark) "It's very dark. " else ""

        if (req is Request.Read) {
            if (read.text.isNotBlank()) {
                return listOfNotNull(read.hint, read.text).joinToString(" ")
            }
            if (!eyes.modelInstalled) return dark + "I don't see any text."
        }

        if (!eyes.modelLoaded) {
            if (!eyes.modelInstalled) {
                return if (read.text.isNotBlank()) {
                    "Scene descriptions aren't set up yet. Here is the text I see. " + read.text
                } else {
                    dark + "Scene descriptions aren't set up yet, and I don't see any text."
                }
            }
            main.post { speaker.say("Starting the scene model. This takes a moment the first time.") }
            eyes.loadModel()?.let { return it }
            refreshModelState()
        }

        val prompt = when (req) {
            is Request.Question -> Prompts.question(req.text, read.text)
            else -> Prompts.describe(read.text)
        }
        val answer = eyes.ask(bitmap, prompt) ?: return dark + "I couldn't describe that. Try again."
        return if (req is Request.Read) "No text. $answer" else dark + answer
    }

    // ------------------------------------------------------------------ settings and model

    private fun applySettings() {
        speaker.setRate(settings.rate)
        camera?.setOrientation(settings.flip, settings.flip)
    }

    private fun refreshModelState() {
        val m = when {
            eyes.modelLoaded -> "Scene model ready"
            eyes.modelInstalled -> "Scene model installed"
            else -> "Scene model not installed"
        }
        SightHub.state.update { it.copy(model = m) }
    }

    // ------------------------------------------------------------------ plumbing

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "BrainOS Sight", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SightService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val read = PendingIntent.getService(
            this, 2, Intent(this, SightService::class.java).setAction(ACTION_READ), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sight)
            .setContentTitle("BrainOS Sight is on")
            .setContentText("Headset button: press to read, press twice to describe")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Read", read).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        var type = 0
        val bt = Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        if (bt) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (type == 0) return false
        return try {
            startForeground(NOTIFICATION_ID, n, type)
            true
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "foreground-failed " + t.javaClass.simpleName + ": " + t.message)
            false
        }
    }

    /** Earbud buttons. Media buttons go to the most recent active media session. */
    private fun startMediaSession() {
        val ms = MediaSession(this, "BrainOS Sight")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (ev == null || ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return true
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    -> earbudTap()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> request(Request.Describe)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> request(Request.Read)
                    else -> return super.onMediaButtonEvent(mediaButtonIntent)
                }
                return true
            }

            override fun onPlay() = earbudTap()
            override fun onPause() = earbudTap()
            override fun onSkipToNext() = request(Request.Describe)
            override fun onSkipToPrevious() = request(Request.Read)
        })
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        ms.isActive = true
        session = ms
    }

    private fun earbudTap() {
        if (speaker.isSpeaking) hush() else ask()
    }

    companion object {
        const val ACTION_START = "sight.START"
        const val ACTION_STOP = "sight.STOP"
        const val ACTION_READ = "sight.READ"
        const val ACTION_DESCRIBE = "sight.DESCRIBE"
        const val ACTION_ASK = "sight.ASK"
        const val ACTION_HUSH = "sight.HUSH"
        const val ACTION_SETTINGS = "sight.SETTINGS"
        const val ACTION_MODEL = "sight.MODEL"
        private const val CHANNEL = "sight"
        private const val NOTIFICATION_ID = 7
    }
}
