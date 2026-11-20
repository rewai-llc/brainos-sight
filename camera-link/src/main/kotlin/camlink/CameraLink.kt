package camlink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bluetooth LE link to a camera, shared by BrainOS and BrainOS Sight. Two kinds are supported:
 *  - the BrainOS Cam (XIAO ESP32S3 Sense running camera/brainos_cam, protocol in camera/PROTOCOL.md);
 *  - the E12 camera headset with its stock firmware (see [E12] and camera/HEADSET_FINDINGS.md).
 * Finds whichever is nearby, keeps it connected, and fetches one JPEG on [snap].
 *
 * Which camera it trusts:
 *  - Cameras that have connected are remembered by Bluetooth address, and from then on only those are
 *    accepted. A look-alike nearby is ignored. A new camera is only taken on after [allowNewCamera]
 *    (the app's "Add or replace camera" button), or on first use.
 *  - A BrainOS Cam with firmware v7+ is also paired with a secret key ([CamAuth]); it answers nobody else.
 *
 * Photos live only in RAM. (Debug builds also keep the last E12 photo and its raw packets in the app's
 * private folder for troubleshooting; release builds never write a photo to disk.)
 */
@SuppressLint("MissingPermission")
class CameraLink(
    context: Context,
    /** Log tag of the app using the link. */
    private val tag: String,
    /** The camera's button was pressed (BrainOS Cam v3+, or a tap on the E12). Called on a Bluetooth thread. */
    private val onButton: () -> Unit = {},
    private val onStatus: (String) -> Unit,
) {
    private enum class Kind { NONE, BRAINOS, E12 }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var control: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var running = false
    @Volatile private var scanning = false
    @Volatile private var pairing = false
    @Volatile private var mtu = 23
    @Volatile private var kind = Kind.NONE
    @Volatile private var lastDevice: BluetoothDevice? = null
    @Volatile private var everReady = false
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    /** Cameras that refused this phone during this run (paired with someone else): don't keep retrying them. */
    private val refused: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // Photo assembly
    private val lock = Any()
    private var pending: CountDownLatch? = null
    private var expected = 0
    private val buf = ByteArrayOutputStream()
    private val e12 = E12.Assembler()
    private val e12Trace = ByteArrayOutputStream()
    private var e12Packets = 0
    private var result: ByteArray? = null

    // E12 full-size photos over the headset's Wi-Fi
    private val wifi = E12Wifi(app, tag)
    @Volatile private var e12Hotspot: Boolean? = null
    @Volatile private var e12Ssid: String? = null
    @Volatile private var ssidLatch: CountDownLatch? = null
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Short spoken hints while a photo is being fetched (for example, "tap Connect"). Set by the app. */
    @Volatile var onNotice: ((String) -> Unit)? = null

    /**
     * E12 only: fetch the full-size photo over the headset's Wi-Fi (sharp, but 10 to 20 s and the phone's
     * Wi-Fi radio is borrowed for it). When false, only the small Bluetooth photo is used (about 3 s).
     */
    @Volatile var preferFullSize: Boolean = true

    /** Leave the headset's Wi-Fi and tell it the transfer is over, so it switches Wi-Fi off. */
    private fun endWifiSession() {
        wifi.disconnect()
        if (ready && kind == Kind.E12) write(E12.transferFinished())
    }

    /** Full-size E12 photos need a network connection to the headset (the INTERNET permission). */
    private val canUseWifi: Boolean =
        app.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    /** If > 0, full-size photos are scaled down so their longer side is at most this many pixels. */
    @Volatile var maxSide: Int = 0

    /** Set when a full-size photo wasn't possible and a small one was sent instead; "" otherwise. */
    @Volatile var lastNote: String = ""
        private set

    val isReady: Boolean get() = ready

    /** Which camera is connected, in plain words ("BrainOS Cam", "E12 headset"), or "". */
    val cameraName: String get() = when (kind) {
        Kind.BRAINOS -> "BrainOS Cam"
        Kind.E12 -> "E12 headset"
        Kind.NONE -> ""
    }

    /** Why the last photo failed, in plain words (shown in the app). Empty after a good photo. */
    @Volatile var lastFailure: String = ""
        private set

    /** Addresses of the cameras this app has settled on (empty before the first connection). */
    private val knownAddresses: Set<String>
        get() = prefs.getStringSet(K_ADDRESSES, null)?.toSet() ?: emptySet()

    /** True on first use and after [allowNewCamera]: the next camera found is accepted and remembered. */
    private val acceptNew: Boolean
        get() = prefs.getBoolean(K_ACCEPT_NEW, true)

    private fun isKnown(address: String) = address in knownAddresses

    private fun remember(address: String) {
        prefs.edit().putStringSet(K_ADDRESSES, knownAddresses + address).putBoolean(K_ACCEPT_NEW, false).apply()
    }

    fun start() {
        if (running) return
        running = true
        refused.clear()
        if (!debuggable) purgeFiles() // nothing left behind by an earlier (debug or older) build
        if (!hasPermissions()) {
            onStatus("needs Bluetooth permission")
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off")
            return
        }
        // The E12 is also the phone's audio headset, and while that link is up it may not advertise.
        // If it's already paired, connect to it directly instead of waiting to see it in a scan.
        val paired = pairedE12()
        if (paired != null) {
            Log.i(tag, "camera-paired-e12")
            connect(paired, auto = false)
        } else {
            scan()
        }
    }

    /** The paired E12 headset, if there is one and it is a camera we know (or a new one is allowed). */
    private fun pairedE12(): BluetoothDevice? = runCatching {
        val known = knownAddresses
        val open = acceptNew
        adapter?.bondedDevices.orEmpty().firstOrNull { d ->
            val n = runCatching { d.name }.getOrNull().orEmpty()
            val isE12 = n.startsWith(E12.NAME_PREFIX, ignoreCase = true) || n.equals("E12", ignoreCase = true)
            isE12 && (open || d.address in known)
        }
    }.getOrNull()

    fun stop() {
        val g = gatt
        val c = control
        val finishTransfer = wifi.isConnected && ready && kind == Kind.E12
        running = false
        ready = false
        pairing = false
        stopScan()
        main.removeCallbacksAndMessages(null)
        gatt = null
        control = null
        kind = Kind.NONE
        synchronized(lock) {
            buf.reset()
            e12.reset()
            e12Trace.reset()
            result = null
            pending?.countDown()
        }
        authLatch?.countDown()
        awaitLatch?.countDown()
        ssidLatch?.countDown()
        // Bluetooth writes can take seconds when the link is struggling: don't hold up the caller.
        Thread({
            if (finishTransfer && g != null && c != null) writeTo(g, c, E12.transferFinished())
            wifi.disconnect()
            if (g != null) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
            if (!debuggable) purgeFiles()
        }, "camlink-stop").start()
        onStatus("off")
    }


    /**
     * Take one photo. Blocks up to [timeoutMs] without progress. Returns JPEG bytes or null.
     * Size 4 = 1600x1200 on the BrainOS Cam. The E12 picks its own size; [size] and [burst] don't apply.
     * [budgetMs] > 0 caps the whole call (all attempts and the E12's Wi-Fi hand-over), for callers that
     * can't wait: when the time is up it gives up with [lastFailure] set.
     */
    fun snap(size: Int = 4, timeoutMs: Long = 8_000, burst: Int = 1, budgetMs: Long = 0): ByteArray? {
        val deadline = if (budgetMs > 0) SystemClock.elapsedRealtime() + budgetMs else Long.MAX_VALUE
        fun left() = deadline - SystemClock.elapsedRealtime()
        lastNote = ""
        if (ready && kind == Kind.E12 && canUseWifi && preferFullSize) {
            val full = snapE12Full(deadline)
            if (full != null) return full
            lastNote = "small photo only: $lastFailure"
            Log.w(tag, "e12-full-failed: $lastFailure; falling back to the Bluetooth photo")
            if (left() < 6_000) return null // not enough time left for the Bluetooth photo either
            Thread.sleep(1_500) // give the headset a moment to leave Wi-Fi mode before asking over Bluetooth
        }
        repeat(2) { attempt ->
            if (left() < 1_000) {
                lastFailure = lastFailure.ifBlank { "the camera took too long" }
                return null
            }
            val jpeg = snapOnce(size, minOf(timeoutMs, left()), burst, deadline)
            if (jpeg != null) return jpeg
            Log.w(tag, "camera-snap-failed attempt=$attempt: $lastFailure")
        }
        return null
    }

    /**
     * Full-size E12 photo. Observed on the real headset: it won't take a photo while its Wi-Fi is on, and it
     * only starts Wi-Fi to hand over saved photos. So each photo is one round:
     *  1. count its photos over Bluetooth, take a stored photo, and confirm the count went up;
     *  2. ask it to start Wi-Fi and join it (Android remembers the approval after the first time);
     *  3. list the photos, download the newest (ours), delete it from the headset;
     *  4. tell the headset the transfer is over and leave its Wi-Fi.
     * Returns the JPEG, or null with [lastFailure] set.
     *
     * Step 3 deletes a photo from the headset, so step 1 is strict: unless the headset's own count proves
     * that a new photo was saved, nothing is downloaded or deleted. (Otherwise "the newest photo" could be
     * one the wearer took earlier, and it would be sent on and erased.)
     */
    private fun snapE12Full(deadline: Long): ByteArray? {
        fun left() = deadline - SystemClock.elapsedRealtime()
        val started = SystemClock.elapsedRealtime()
        if (wifi.isConnected) endWifiSession() // a leftover session would block the camera
        // 1. Take the photo over Bluetooth and make sure it was saved.
        val countBefore = askCount() ?: askCount()
            ?: return fail("the headset didn't say how many photos it has")
        val idle = CountDownLatch(1)
        photoBusySeen = false
        photoIdle = idle
        val ack = request(E12.takeStoredPhoto(), E12.CMD_TAKE_PHOTO, 5_000)
        Log.i(tag, "e12-take ack=${ack != null} countBefore=$countBefore")
        if (ack == null) {
            photoIdle = null
            return fail("the headset didn't take the photo")
        }
        // The headset reports "taking a photo" (event 0x45, first byte 1) and then "done" (0);
        // seen on the real headset: about 3 s. The count only goes up after "done".
        val done = idle.await(minOf(8_000, maxOf(left(), 0)), TimeUnit.MILLISECONDS)
        photoIdle = null
        Log.i(tag, "e12-photo-done=$done")
        var countAfter: Int? = null
        val saveDeadline = minOf(SystemClock.elapsedRealtime() + 5_000, deadline)
        while (SystemClock.elapsedRealtime() < saveDeadline) {
            countAfter = askCount() ?: countAfter
            if (countAfter != null && countAfter > countBefore) break
            Thread.sleep(500)
        }
        Log.i(tag, "e12-saved countAfter=$countAfter")
        if (countAfter == null || countAfter <= countBefore) {
            return fail("the headset didn't confirm it saved the photo")
        }
        // 2. Wi-Fi.
        val tPhoto = SystemClock.elapsedRealtime()
        var tWifiOn = tPhoto
        val modes = e12Hotspot?.let { listOf(it) } ?: listOf(true, false)
        var why = "the headset's Wi-Fi didn't start"
        for (hot in modes) {
            if (left() < 5_000) {
                why = "the headset's Wi-Fi took too long"
                break
            }
            val ssid = askWifiName(hot, minOf(30_000, left()))
            if (ssid == null) {
                why = "the headset didn't give its Wi-Fi name"
                continue
            }
            tWifiOn = SystemClock.elapsedRealtime()
            Log.i(tag, "e12-wifi joining hotspot=$hot")
            val firstTime = !prefs.getBoolean("e12_wifi_ok", false)
            if (firstTime) onNotice?.invoke("Joining the headset's Wi-Fi. If the phone asks, tap Connect.")
            // The hotspot announces its name a few seconds before it is on the air. Measured on the real headset:
            // waiting 3 s first, the join took 9 s; joining at once, Android's first search missed the network and
            // the next one came 25 s later. So always wait.
            Thread.sleep(3_000)
            var err: String? = null
            val tries = if (firstTime) 3 else 4
            for (attempt in 1..tries) {
                if (left() < 3_000) {
                    err = "the headset's Wi-Fi took too long"
                    break
                }
                err = wifi.connect(ssid, E12.WIFI_PASSWORD, hot, minOf(90_000, left()))
                Log.i(tag, "e12-wifi attempt=$attempt result=${err ?: "connected"}")
                if (err == null) break
                if (attempt < tries) {
                    if (firstTime) onNotice?.invoke("Trying the headset's Wi-Fi again. Tap Connect if asked.")
                    Thread.sleep(if (firstTime) 4_000 else 1_500)
                }
            }
            if (err == null) {
                e12Hotspot = hot
                prefs.edit().putBoolean("e12_wifi_ok", true).apply()
                break
            }
            why = err
        }
        if (!wifi.isConnected) return fail(why)
        val tJoined = SystemClock.elapsedRealtime()
        val hot = e12Hotspot ?: true
        // 3. Download the newest photo (the one we just took) and remove it from the headset.
        val files = listPhotos(hot) ?: return fail("couldn't list the headset's photos")
        Log.i(tag, "e12-list photos=${files.size}")
        val target = E12.newest(files) ?: return fail("the headset's photo list was empty")
        repeat(4) { attempt ->
            if (left() < 2_000) return fail("the photo download took too long")
            val bytes = wifi.get(target.url, minOf(20_000, left()).toInt())
            if (bytes != null && E12.looksLikeJpeg(bytes) && endsJpeg(bytes)) {
                val deleted = wifi.get(E12.deleteUrl(target, hot), 5_000) != null
                val out = shrink(bytes)
                lastFailure = ""
                lastTransferMs = SystemClock.elapsedRealtime() - started
                lastBytes = out.size
                Log.i(tag, "camera-photo kind=E12-wifi bytes=${bytes.size} sent=${out.size} ms=$lastTransferMs deleted=$deleted")
                val tDone = SystemClock.elapsedRealtime()
                Log.i(tag, "e12-timing photo=${tPhoto - started} wifiOn=${tWifiOn - tPhoto} join=${tJoined - tWifiOn} download=${tDone - tJoined}")
                endWifiSession() // 4.
                return out
            }
            Log.w(tag, "e12-download incomplete attempt=$attempt size=${bytes?.size ?: -1}")
            Thread.sleep(700)
        }
        return fail("the photo download failed")
    }

    /** How many photos the headset has saved (Bluetooth), or null if it didn't say. */
    private fun askCount(): Int? =
        request(E12.command(E12.CMD_THUMBNAIL_COUNT, 0), E12.EVT_PHOTO_COUNT, 2_000)?.let { E12.photoCount(it) }

    // One outstanding "send a command and wait for this reply" at a time.
    /** Counted down when the headset reports it has finished taking a photo. */
    @Volatile private var photoIdle: CountDownLatch? = null
    @Volatile private var photoBusySeen = false

    @Volatile private var awaitCmd = -1
    @Volatile private var awaitLatch: CountDownLatch? = null
    @Volatile private var awaitPayload: ByteArray? = null

    /** Sends [cmd] and waits for the headset's event [replyCmd]; returns its payload, or null. */
    private fun request(cmd: ByteArray, replyCmd: Int, timeoutMs: Long): ByteArray? {
        val latch = CountDownLatch(1)
        awaitPayload = null
        awaitCmd = replyCmd
        awaitLatch = latch
        if (!write(cmd)) {
            awaitCmd = -1
            awaitLatch = null
            return null
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        awaitCmd = -1
        awaitLatch = null
        return awaitPayload
    }

    /** A full-size photo failed: record why, and release the headset from Wi-Fi mode so it answers again. */
    private fun fail(why: String): ByteArray? {
        lastFailure = why
        endWifiSession()
        return null
    }

    /** Asks the headset to start its Wi-Fi and waits for its network name. */
    private fun askWifiName(hotspot: Boolean, timeoutMs: Long): String? {
        val latch = CountDownLatch(1)
        e12Ssid = null
        ssidLatch = latch
        if (!write(E12.getWifiInfo(hotspot))) {
            ssidLatch = null
            return null
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS) // the headset's Wi-Fi can take a while to come up
        ssidLatch = null
        val got = e12Ssid
        if (got != null) {
            prefs.edit().putString("e12_ssid", got).apply()
            return got
        }
        // No answer: it may already be in Wi-Fi mode from an earlier try. Use the name it gave last time.
        val remembered = prefs.getString("e12_ssid", null)
        if (remembered != null) Log.i(tag, "e12-wifi using the remembered network name")
        return remembered
    }

    private fun listPhotos(hotspot: Boolean): List<E12.StoredFile>? {
        val url = if (hotspot) E12.HOTSPOT_BASE + "app/getfilelist" else E12.DIRECT_BASE + "?custom=1&cmd=3015"
        val body = wifi.get(url, 8_000, maxBytes = 2 * 1024 * 1024) ?: return null
        val text = String(body, Charsets.UTF_8)
        return if (hotspot) E12.parseHotspotList(text) else E12.parseDirectList(text)
    }

    private fun endsJpeg(b: ByteArray): Boolean {
        // A JPEG ends with FF D9; some cameras pad a few bytes after it.
        for (i in b.size - 2 downTo maxOf(0, b.size - 64)) {
            if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD9.toByte()) return true
        }
        return false
    }

    /** Scales a full-size photo down to [maxSide] when set (BrainOS sends photos to Gemini). */
    private fun shrink(jpeg: ByteArray): ByteArray {
        val limit = maxSide
        if (limit <= 0) return jpeg
        return runCatching {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val side = maxOf(bmp.width, bmp.height)
            if (side <= limit) return jpeg
            val f = limit.toFloat() / side
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
            val out = ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }.getOrDefault(jpeg)
    }

    /** How long the last good photo took from request to last byte, and its size. */
    @Volatile var lastTransferMs: Long = 0
        private set
    @Volatile var lastBytes: Int = 0
        private set

    private fun snapOnce(size: Int, timeoutMs: Long, burst: Int, deadline: Long): ByteArray? {
        if (!ready) {
            lastFailure = "camera not connected"
            return null
        }
        val latch = CountDownLatch(1)
        synchronized(lock) {
            buf.reset()
            e12.reset()
            e12Trace.reset()
            e12Packets = 0
            expected = 0
            result = null
            lastFailure = ""
            pending = latch
        }
        val started = SystemClock.elapsedRealtime()
        val cmd = if (kind == Kind.E12) {
            E12.takePhoto()
        } else {
            val chunk = (mtu - 3).coerceIn(20, 509)
            byteArrayOf(0x01, size.toByte(), (chunk and 0xFF).toByte(), (chunk shr 8).toByte(), burst.coerceIn(1, 3).toByte())
        }
        if (!write(cmd)) {
            synchronized(lock) { pending = null }
            lastFailure = "couldn't send the command"
            return null
        }
        // Wait while data keeps arriving: give up only after [timeoutMs] with no progress,
        // or 30 s in total, so a big photo on a slow link isn't cut off halfway.
        var arrived = false
        var lastSize = -1
        var lastProgress = started
        while (true) {
            if (latch.await(500, TimeUnit.MILLISECONDS)) {
                arrived = true
                break
            }
            val now = SystemClock.elapsedRealtime()
            val got = synchronized(lock) { if (kind == Kind.E12) e12.size else buf.size() }
            if (got != lastSize) {
                lastSize = got
                lastProgress = now
            }
            if (now - lastProgress > timeoutMs || now - started > 30_000 || now > deadline) break
        }
        synchronized(lock) {
            pending = null
            if (!arrived) {
                val got = if (kind == Kind.E12) e12.size else buf.size()
                lastFailure = when {
                    expected > 0 -> "timed out at ${got / 1024} of ${expected / 1024} KB"
                    got > 0 -> "timed out after ${got / 1024} KB"
                    else -> "no reply from the camera"
                }
            } else if (result != null) {
                lastFailure = ""
                lastTransferMs = SystemClock.elapsedRealtime() - started
                lastBytes = result!!.size
                Log.i(tag, "camera-photo kind=$kind bytes=$lastBytes ms=$lastTransferMs mtu=$mtu")
            }
            if (kind == Kind.E12) saveE12Trace(result)
            return result
        }
    }

    /**
     * DEBUG BUILDS ONLY. Keeps the raw Bluetooth packets of the last E12 photo (and the photo itself) in the
     * app's private folder, so a bad photo can be examined. Each packet is stored as a 2-byte length then its
     * bytes. Release builds never write photos to disk, and delete any such files when the camera starts or stops.
     * Pull with: adb shell "run-as <package> cat files/e12_raw.bin > /sdcard/Download/e12_raw.bin"
     */
    private fun saveE12Trace(jpeg: ByteArray?) {
        if (!debuggable) return
        runCatching {
            File(app.filesDir, "e12_raw.bin").writeBytes(e12Trace.toByteArray())
            if (jpeg != null) File(app.filesDir, "e12_last.jpg").writeBytes(jpeg)
            Log.i(tag, "e12-trace saved packets=$e12Packets bytes=${e12Trace.size()} photo=${jpeg?.size ?: 0}")
        }
    }

    /** Removes every troubleshooting file this class may have written (photos and raw packets). */
    private fun purgeFiles() {
        runCatching {
            app.filesDir.listFiles()?.forEach { f ->
                val n = f.name
                if (n == "e12_raw.bin" || n == "e12_last.jpg" || (n.startsWith("e12_probe_") && n.endsWith(".jpg"))) f.delete()
            }
        }
    }

    /**
     * Developer test for the E12 (debug builds): tries the photo settings and "pull image" commands the stock
     * firmware has but GlassX never uses, and logs what each one sends back over Bluetooth (size, time, picture
     * dimensions). See camera/BT_FIRMWARE_NOTES.md. Runs for about two minutes. Call from a background thread.
     * [progress] gets short spoken updates.
     */
    fun probeE12(progress: (String) -> Unit): String {
        if (!debuggable) return "The camera test is only available in debug builds."
        if (!ready || kind != Kind.E12) return "The headset camera isn't connected."
        val lines = mutableListOf<String>()
        fun tryOne(label: String, setup: ByteArray?, trigger: ByteArray, quietMs: Long = 6_000) {
            if (setup != null) {
                write(setup)
                Thread.sleep(500)
            }
            val r = captureE12(trigger, quietMs)
            val line = "e12-probe $label -> $r"
            Log.i(tag, line)
            lines += line
            Thread.sleep(800)
        }
        progress("Testing the headset camera. This takes about two minutes.")
        tryOne("baseline", null, E12.takePhoto())
        // Photo mode (0x50). GlassX sends single-byte settings as ASCII digits, so try those and raw values.
        // Ends on 0x30, the most likely default.
        progress("Trying photo modes.")
        for (v in intArrayOf(0x31, 0x32, 0x33, 0x01, 0x02, 0x00, 0x30)) {
            tryOne("mode-%02X".format(v), E12.command(0x50, v), E12.takePhoto())
        }
        // Pull commands: 0x97 high-quality image, 0x36 image, 0x37 thumbnail. Save one full photo first so
        // there is something to pull.
        progress("Trying the pull commands.")
        write(E12.takeStoredPhoto())
        Thread.sleep(5_000)
        for (cmd in intArrayOf(0x97, 0x36, 0x37)) {
            for (p in intArrayOf(0x31, 0x30, 0x01, 0x00)) {
                tryOne("pull-%02X-%02X".format(cmd, p), null, E12.command(cmd, p), quietMs = 5_000)
            }
        }
        write(E12.transferFinished())
        progress("Camera test finished.")
        return lines.joinToString("\n")
    }

    /** Sends [trigger] and records whatever comes back on the photo channel. Returns a one-line summary. */
    private fun captureE12(trigger: ByteArray, quietMs: Long): String {
        val latch = CountDownLatch(1)
        synchronized(lock) {
            e12.reset()
            e12Trace.reset()
            e12Packets = 0
            result = null
            lastFailure = ""
            pending = latch
        }
        val started = SystemClock.elapsedRealtime()
        if (!write(trigger)) {
            synchronized(lock) { pending = null }
            return "write failed"
        }
        var lastBytes = -1
        var lastProgress = started
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            val now = SystemClock.elapsedRealtime()
            val got = synchronized(lock) { e12Trace.size() }
            if (got != lastBytes) {
                lastBytes = got
                lastProgress = now
            }
            if (now - lastProgress > quietMs || now - started > 60_000) break
        }
        val ms = SystemClock.elapsedRealtime() - started
        synchronized(lock) {
            pending = null
            val jpeg = result
            val raw = e12Trace.size()
            val packets = e12Packets
            if (jpeg == null) {
                return "no photo (packets=$packets raw=$raw ms=$ms${if (lastFailure.isNotBlank()) " why=$lastFailure" else ""})"
            }
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            val kbps = if (ms > 0) jpeg.size * 1000L / ms / 1024 else 0
            if (o.outWidth >= 640) runCatching { File(app.filesDir, "e12_probe_${o.outWidth}.jpg").writeBytes(jpeg) }
            return "photo ${o.outWidth}x${o.outHeight} bytes=${jpeg.size} ms=$ms (${kbps} KB/s) packets=$packets"
        }
    }

    /** Someone started talking: power the camera up so a photo is instant if needed. */
    fun warm() {
        if (ready && kind == Kind.BRAINOS) write(byteArrayOf(0x02))
    }

    fun setOrientation(vflip: Boolean, hmirror: Boolean) {
        if (ready && kind == Kind.BRAINOS) write(byteArrayOf(0x03, if (vflip) 1 else 0, if (hmirror) 1 else 0))
    }

    // ---------------------------------------------------------------- scanning

    private fun isCamera(result: ScanResult): Boolean {
        val address = result.device.address
        if (address in refused) return false
        if (isKnown(address)) return true
        if (!acceptNew) return false // only ever the cameras this app settled on
        val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        if (SERVICE in uuids || E12.SERVICE in uuids) return true
        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
        return name != null && name.startsWith(E12.NAME_PREFIX, ignoreCase = true)
    }

    /** Devices seen while scanning, logged once each in debug builds so a failed search can be diagnosed. */
    private val seen = HashSet<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (debuggable && seen.size < 60 && seen.add(result.device.address)) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "?"
                Log.d(tag, "scan-seen name=$name uuids=${result.scanRecord?.serviceUuids} rssi=${result.rssi}")
            }
            if (!running || gatt != null || !isCamera(result)) return
            stopScan()
            connect(result.device, auto = false)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatus("scan failed ($errorCode)")
            retryLater()
        }
    }

    private fun scan() {
        if (!running || scanning || gatt != null) return
        val scanner = adapter?.bluetoothLeScanner ?: return retryLater()
        val known = knownAddresses
        val open = acceptNew || known.isEmpty()
        onStatus(if (open) "searching" else "searching for your camera")
        // Once the camera is known, scan for its address only: Android pauses unfiltered scans while the
        // screen is off, and a filtered one keeps working. A search for a new camera has no filter because
        // the E12 doesn't advertise its service; it's recognised by name in isCamera().
        val filters = if (open) null else known.map { ScanFilter.Builder().setDeviceAddress(it).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            // Scanning is power-hungry: pause after 30 s and try again later.
            main.postDelayed({
                if (scanning) {
                    stopScan()
                    retryLater()
                }
            }, 30_000)
        } catch (t: Throwable) {
            Log.w(tag, "camera-scan-failed " + t.javaClass.simpleName)
            retryLater()
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun retryLater() {
        if (!running) return
        main.postDelayed({ scan() }, 10_000)
    }

    // ---------------------------------------------------------------- connection

    /** [auto] = let Android reconnect whenever the camera reappears (works with the screen off). */
    private fun connect(device: BluetoothDevice, auto: Boolean) {
        onStatus(if (auto) "waiting for the camera" else "connecting")
        if (device != lastDevice) everReady = false
        lastDevice = device
        Log.i(tag, "camera-connect auto=$auto")
        val g = device.connectGatt(app, auto, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        if (!auto && g != null) {
            // A direct connection that stalls halfway (no MTU reply, no services) would otherwise sit at
            // "connecting" forever. Pairing waits for a button press, so it gets its own time.
            main.postDelayed({
                if (running && gatt === g && !ready && !pairing) {
                    Log.w(tag, "camera-connect stalled; starting over")
                    runCatching { g.disconnect() }
                    runCatching { g.close() }
                    dropLink(g, status = -1)
                }
            }, CONNECT_TIMEOUT_MS)
        }
    }

    /** The link to [g] is gone (reported by Android, or given up on): clean up and look for the camera again. */
    private fun dropLink(g: BluetoothGatt, status: Int) {
        if (gatt !== g) return // already dealt with (stopped, timed out, or replaced)
        ready = false
        pairing = false
        control = null
        kind = Kind.NONE
        releaseWriteGate()
        gatt = null
        synchronized(lock) { pending?.countDown() }
        authLatch?.countDown()
        awaitLatch?.countDown()
        if (!running) return
        Log.i(tag, "camera-disconnected status=$status everReady=$everReady")
        // A direct connection that never got going (wrong address, out of range): go back to scanning.
        if (!everReady) lastDevice = null
        val last = lastDevice
        if (last != null) {
            main.postDelayed({ if (running && gatt == null) connect(last, auto = true) }, 1_000)
        } else {
            main.postDelayed({ scan() }, 1_500)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && running) {
                if (!g.requestMtu(517)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runCatching { g.close() }
                dropLink(g, status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            // The fast radio mode (2M PHY) roughly doubles photo transfer speed where supported.
            runCatching {
                g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(tag, "camera-services-failed status=$status")
                g.disconnect()
                return
            }
            notifyQueue.clear()
            val ours = g.getService(SERVICE)
            val e12svc = g.getService(E12.SERVICE)
            when {
                ours?.getCharacteristic(CONTROL) != null && ours.getCharacteristic(DATA) != null -> {
                    kind = Kind.BRAINOS
                    control = ours.getCharacteristic(CONTROL)
                    notifyQueue.add(ours.getCharacteristic(DATA))
                }
                e12svc?.getCharacteristic(E12.WRITE) != null && e12svc.getCharacteristic(E12.PHOTO) != null -> {
                    kind = Kind.E12
                    control = e12svc.getCharacteristic(E12.WRITE)
                    notifyQueue.add(e12svc.getCharacteristic(E12.PHOTO))
                    e12svc.getCharacteristic(E12.EVENTS)?.let { notifyQueue.add(it) }
                }
                else -> {
                    onStatus("not a camera BrainOS knows")
                    refused.add(g.device.address)
                    lastDevice = null
                    g.disconnect()
                    return
                }
            }
            Log.i(tag, "camera-kind $kind mtu=$mtu")
            enableNextNotification(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            releaseWriteGate()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready = false
                onStatus("setup failed")
                g.disconnect()
                return
            }
            enableNextNotification(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            route(c.uuid, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // On Android 13+ the callback above is the one that's used; don't handle a packet twice.
            if (Build.VERSION.SDK_INT >= 33) return
            route(c.uuid, c.value ?: return)
        }
    }

    /** Turns on notifications one characteristic at a time (Android allows one GATT operation at a time). */
    private fun enableNextNotification(g: BluetoothGatt) {
        val c = notifyQueue.removeFirstOrNull()
        if (c == null) {
            if (kind == Kind.BRAINOS) {
                // The pairing handshake waits for replies, so it can't run on this Bluetooth callback thread.
                Thread({ authenticate(g) }, "camlink-pair").start()
            } else {
                becomeReady(g, "connected (E12 headset)")
                // Ask which Wi-Fi mode it uses, for full-size photos later.
                main.postDelayed({ if (ready) write(E12.getCustomer()) }, 300)
            }
            return
        }
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return enableNextNotification(g)
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun becomeReady(g: BluetoothGatt, status: String) {
        if (!running || gatt !== g) return
        pairing = false
        ready = true
        everReady = true
        remember(g.device.address)
        onStatus(status)
    }

    // ---------------------------------------------------------------- pairing (BrainOS Cam v7+)

    @Volatile private var authWant = -1
    @Volatile private var authLatch: CountDownLatch? = null
    @Volatile private var authReply: ByteArray? = null

    private fun authRequest(cmd: ByteArray, reply: Int, timeoutMs: Long): ByteArray? {
        val latch = CountDownLatch(1)
        authReply = null
        authWant = reply
        authLatch = latch
        if (!write(cmd)) {
            authWant = -1
            authLatch = null
            return null
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        authWant = -1
        authLatch = null
        return authReply
    }

    private fun loadKey(address: String): ByteArray? = runCatching {
        prefs.getString(K_KEY_PREFIX + address, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?.takeIf { it.size == CamAuth.KEY_BYTES }
    }.getOrNull()

    private fun saveKey(address: String, key: ByteArray?) {
        prefs.edit().apply {
            if (key == null) remove(K_KEY_PREFIX + address)
            else putString(K_KEY_PREFIX + address, Base64.encodeToString(key, Base64.NO_WRAP))
        }.apply()
    }

    /**
     * Proves to the camera that this app is paired with it, or pairs if it can (see [CamAuth]).
     * A camera with older firmware doesn't answer the greeting; it works as before, unlocked.
     * Runs on its own thread.
     */
    private fun authenticate(g: BluetoothGatt) {
        val address = g.device.address
        fun alive() = running && gatt === g
        fun greet(): CamAuth.Hello? = authRequest(CamAuth.hello(), CamAuth.HELLO, 1_500)?.let { CamAuth.parseHello(it) }

        var hello = greet() ?: greet()
        if (!alive()) return
        if (hello == null) {
            Log.i(tag, "camera-auth legacy firmware")
            becomeReady(g, "connected (older camera firmware: not locked to this phone)")
            return
        }
        var key = loadKey(address)
        if (key != null && hello.claimed) {
            if (CamAuth.accepted(authRequest(CamAuth.prove(key, hello.nonce), CamAuth.PROVE, 2_000))) {
                Log.i(tag, "camera-auth ok")
                becomeReady(g, "connected")
                return
            }
            if (!alive()) return
            // The camera no longer knows our key (it was reset): pair again.
            Log.i(tag, "camera-auth key refused; pairing again")
            saveKey(address, null)
            key = null
            hello = greet() ?: return g.disconnect()
        }
        // Pair: allowed when the camera has no owner yet, or after its button is pressed.
        pairing = true
        val deadline = SystemClock.elapsedRealtime() + PAIRING_WINDOW_MS
        var asked = false
        while (alive()) {
            val h = hello ?: break
            if (!h.claimed || h.pairingOpen) {
                val fresh = CamAuth.newKey()
                if (CamAuth.accepted(authRequest(CamAuth.claim(fresh), CamAuth.PROVE, 2_000))) {
                    saveKey(address, fresh)
                    Log.i(tag, "camera-auth paired")
                    becomeReady(g, "connected")
                    return
                }
            }
            if (!asked) {
                asked = true
                onStatus("press the camera's button to pair it with this phone")
            }
            if (SystemClock.elapsedRealtime() > deadline) break
            Thread.sleep(1_500)
            hello = greet()
        }
        pairing = false
        if (!alive()) return
        Log.i(tag, "camera-auth not paired")
        onStatus("this camera is paired with another phone (hold its button 10 s to reset it)")
        refused.add(address)
        lastDevice = null
        g.disconnect()
    }

    private fun route(uuid: UUID, value: ByteArray) {
        when (uuid) {
            DATA -> onPacket(value)
            E12.PHOTO -> onE12Photo(value)
            E12.EVENTS -> onE12Event(value)
        }
    }

    /**
     * Android allows one Bluetooth write in flight at a time and silently refuses the next until the
     * previous one is confirmed (onCharacteristicWrite). So writes wait their turn, and are retried if busy.
     */
    private val writeGate = java.util.concurrent.Semaphore(1)

    private fun releaseWriteGate() {
        if (writeGate.availablePermits() == 0) writeGate.release()
    }

    private fun write(value: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = control ?: return false
        return writeTo(g, c, value)
    }

    private fun writeTo(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        repeat(10) { attempt ->
            // Wait for the previous write to be confirmed (but don't hang forever if a confirmation was lost).
            if (!writeGate.tryAcquire(1_500, TimeUnit.MILLISECONDS)) Log.w(tag, "camera-write no confirmation for the previous write")
            val ok = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    c.value = value
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            } catch (t: Throwable) {
                false // the link was closed under us
            }
            if (ok) return true
            releaseWriteGate()
            if (gatt !== g) return false // disconnected: no point retrying
            Log.w(tag, "camera-write busy attempt=$attempt")
            Thread.sleep(60)
        }
        return false
    }

    private fun onE12Photo(p: ByteArray) {
        synchronized(lock) {
            if (debuggable && e12Trace.size() < 4_000_000) {
                e12Trace.write(p.size shr 8)
                e12Trace.write(p.size and 0xFF)
                e12Trace.write(p)
                e12Packets++
            }
            val latch = pending ?: return
            when (val step = e12.feed(p)) {
                is E12.Step.Done -> {
                    result = step.jpeg
                    latch.countDown()
                }
                is E12.Step.Failed -> {
                    lastFailure = step.why
                    Log.w(tag, "e12-photo-failed: ${step.why}")
                    result = null
                    latch.countDown()
                }
                E12.Step.More -> Unit
            }
        }
    }

    private fun onE12Event(p: ByteArray) {
        val e = E12.event(p) ?: return
        if (debuggable) {
            Log.d(tag, "e12-event cmd=0x%02X payload=%s".format(e.cmd, e.payload.joinToString("") { "%02X".format(it) }.take(80)))
        }
        if (e.cmd == awaitCmd) {
            awaitPayload = e.payload
            awaitLatch?.countDown()
        }
        when (e.cmd) {
            E12.EVT_STATE -> if (E12.takingPhoto(e.payload)) photoBusySeen = true else if (photoBusySeen) photoIdle?.countDown()
            E12.GESTURE_CLICK -> if (ready) onButton()
            E12.CMD_CUSTOMER -> {
                e12Hotspot = E12.hotspotModeFromCustomer(e.payload)
                Log.i(tag, "e12-customer hotspot=$e12Hotspot")
            }
            E12.EVT_WIFI_NAME -> {
                E12.wifiName(e.payload)?.let { e12Ssid = it }
                Log.i(tag, "e12-wifi-name received=${e12Ssid != null}")
                ssidLatch?.countDown()
            }
        }
    }

    /** BrainOS Cam packet format: camera/PROTOCOL.md. */
    private fun onPacket(p: ByteArray) {
        if (p.isEmpty()) return
        val type = p[0].toInt()
        if (type == 0x05) {
            if (ready) onButton() // while pairing, the press only opens the camera's pairing window
            return
        }
        if (type == CamAuth.HELLO || type == CamAuth.PROVE) {
            if (type == authWant) {
                authReply = p
                authLatch?.countDown()
            }
            return
        }
        synchronized(lock) {
            val latch = pending ?: return
            when (type) {
                0x01 -> if (p.size >= 5) {
                    val len = u32(p, 1)
                    // A length that makes no sense (or would exhaust memory) is refused outright.
                    if (len <= 0 || len > MAX_PHOTO_BYTES) {
                        lastFailure = "the camera sent a bad photo size"
                        expected = 0
                        result = null
                        latch.countDown()
                        return
                    }
                    expected = len
                    buf.reset()
                }
                0x02 -> if (p.size > 5 && expected > 0) {
                    val off = u32(p, 1)
                    if (off != buf.size() || buf.size() + (p.size - 5) > expected) {
                        // A notification went missing (or the camera sent too much): fail this photo (caller retries once).
                        Log.w(tag, "camera-gap expected=${buf.size()} got=$off")
                        lastFailure = "data went missing at ${buf.size() / 1024} of ${expected / 1024} KB"
                        expected = 0
                        result = null
                        latch.countDown()
                        return
                    }
                    buf.write(p, 5, p.size - 5)
                }
                0x03 -> {
                    result = if (expected > 0 && buf.size() == expected) buf.toByteArray() else null
                    if (result == null) lastFailure = "photo incomplete (${buf.size() / 1024} of ${expected / 1024} KB)"
                    buf.reset()
                    latch.countDown()
                }
                0x04 -> {
                    lastFailure = when (p.getOrNull(1)?.toInt()) {
                        1 -> "the camera wouldn't start"
                        2 -> "the camera couldn't take the picture"
                        3 -> "the camera is locked (not paired with this phone)"
                        else -> "camera error"
                    }
                    Log.w(tag, "camera-error code=" + (p.getOrNull(1)?.toInt() ?: -1))
                    result = null
                    latch.countDown()
                }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val need = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return need.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6e7a0001-b5a3-4c1c-9b3e-6272a1f0c0de")
        val CONTROL: UUID = UUID.fromString("6e7a0002-b5a3-4c1c-9b3e-6272a1f0c0de")
        val DATA: UUID = UUID.fromString("6e7a0003-b5a3-4c1c-9b3e-6272a1f0c0de")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val PREFS = "camera_link"
        private const val K_ADDRESSES = "camera_addresses"
        private const val K_ACCEPT_NEW = "camera_accept_new"
        private const val K_KEY_PREFIX = "camera_key_"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val PAIRING_WINDOW_MS = 60_000L

        /** Largest photo accepted over Bluetooth (a 1600x1200 JPEG is well under 1 MB). */
        const val MAX_PHOTO_BYTES = 8 * 1024 * 1024

        /**
         * Let the next camera found be accepted and remembered alongside the ones already known
         * (the "Add or replace camera" button). Takes effect the next time the camera link starts.
         */
        fun allowNewCamera(context: Context) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_ACCEPT_NEW, true).apply()
        }

        /** Forget every remembered camera and this app's pairing keys for them. */
        fun forgetCameras(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val e = prefs.edit().remove(K_ADDRESSES).putBoolean(K_ACCEPT_NEW, true).remove("e12_ssid").remove("e12_wifi_ok")
            prefs.all.keys.filter { it.startsWith(K_KEY_PREFIX) }.forEach { e.remove(it) }
            e.apply()
        }

        fun u32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or
                ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or
                ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}
