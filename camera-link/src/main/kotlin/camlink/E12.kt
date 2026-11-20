package camlink

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * The E12 camera headset ("Hi, Luma" / GlassX family), spoken to with its stock firmware.
 * The protocol was read out of the GlassX app and confirmed on a real headset:
 * see camera/HEADSET_FINDINGS.md. Pure logic, no Android, so it is unit-tested.
 */
object E12 {
    val SERVICE: UUID = UUID.fromString("0000aa12-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000aa13-0000-1000-8000-00805f9b34fb")
    val EVENTS: UUID = UUID.fromString("0000aa14-0000-1000-8000-00805f9b34fb")
    val PHOTO: UUID = UUID.fromString("0000aa15-0000-1000-8000-00805f9b34fb")

    /** The headset advertises as "E12-" plus the end of its address, e.g. "E12-CC60". */
    const val NAME_PREFIX = "E12-"

    const val CMD_TAKE_PHOTO = 0x22
    const val PHOTO_TO_PHONE = 0x31 // the "AI photo": sent straight back over Bluetooth
    const val PHOTO_TO_STORAGE = 0x30 // saved on the headset, fetched later over Wi-Fi

    // Gestures the headset reports on the event channel.
    const val GESTURE_FRONT_SWIPE = 0x07
    const val GESTURE_BACK_SWIPE = 0x08
    const val GESTURE_CLICK = 0x09
    const val GESTURE_DOUBLE_CLICK = 0x10
    const val GESTURE_TRIPLE_CLICK = 0x11

    const val PHOTO_START = 0x97
    const val PHOTO_DATA = 0x98
    const val PHOTO_END = 0x99

    /** Command packet: AB 55 len(2) cmd payload… crc, where len = payload + 2 and crc = (cmd + payload) & 0xFF. */
    fun command(cmd: Int, vararg payload: Int): ByteArray {
        val out = ByteArray(6 + payload.size)
        val len = payload.size + 2
        out[0] = 0xAB.toByte()
        out[1] = 0x55
        out[2] = (len shr 8).toByte()
        out[3] = len.toByte()
        out[4] = cmd.toByte()
        payload.forEachIndexed { i, v -> out[5 + i] = v.toByte() }
        out[out.size - 1] = ((cmd + payload.sum()) and 0xFF).toByte()
        return out
    }

    /** Ask for one photo sent straight to the phone: AB 55 00 03 22 31 53 (small: 320x180 on our headset). */
    fun takePhoto(): ByteArray = command(CMD_TAKE_PHOTO, PHOTO_TO_PHONE)

    /** Take a full-size photo and save it on the headset (fetched afterwards over Wi-Fi). */
    fun takeStoredPhoto(): ByteArray = command(CMD_TAKE_PHOTO, PHOTO_TO_STORAGE)

    const val CMD_CUSTOMER = 0x64
    const val CMD_THUMBNAIL_COUNT = 0x40 // "how many photos do you have" (GlassX asks this before Wi-Fi)
    const val CMD_WIFI_INFO = 0x39
    const val EVT_WIFI_NAME = 0x25

    /** "Who made you": the reply tells which Wi-Fi mode the camera uses. Sent exactly as the app does. */
    fun getCustomer(): ByteArray = byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x02, 0x64, 0x64)

    /** Turn on the headset's Wi-Fi and report its network name. [hotspot] = the headset is the access point. */
    fun getWifiInfo(hotspot: Boolean): ByteArray = command(CMD_WIFI_INFO, if (hotspot) 0x30 else 0x31)

    /**
     * End the Wi-Fi transfer session so the headset turns its Wi-Fi off and answers Bluetooth again.
     * GlassX sends 0x44 with (0x30, 0x01) when nothing was imported, which never implies photos may be deleted.
     */
    fun transferFinished(): ByteArray = command(0x44, 0x30, 0x01)

    /** The headset's Wi-Fi password, fixed in the GlassX app. */
    const val WIFI_PASSWORD = "12345678"

    /**
     * Customer reply (event 0x64): 8 bytes, two 4-character codes. If the first one starts with "T",
     * the camera is its own Wi-Fi hotspot at 192.168.169.1; otherwise it uses Wi-Fi Direct at 192.168.49.207.
     */
    fun hotspotModeFromCustomer(payload: ByteArray): Boolean? {
        if (payload.size < 8) return null
        val first = String(payload, 0, 4, Charsets.US_ASCII).trim('\u0000', ' ')
        return first.uppercase().startsWith("T")
    }

    /** Wi-Fi name reply (event 0x25): the network name as text. */
    fun wifiName(payload: ByteArray): String? =
        String(payload, Charsets.UTF_8).trim('\u0000', ' ').takeIf { it.length >= 5 }

    const val HOTSPOT_BASE = "http://192.168.169.1/"
    const val DIRECT_BASE = "http://192.168.49.207/"

    /** A photo stored on the headset. [sortKey] orders photos oldest to newest. */
    data class StoredFile(val name: String, val url: String, val size: Int, val sortKey: String)

    /**
     * Hotspot-mode list (GET app/getfilelist), JSON:
     * {"result":0,"info":[{"folder":…,"count":…,"files":[{"name":…,"createtimestr":…,"size":…,"type":…}]}]}
     * Only photos are returned.
     */
    fun parseHotspotList(json: String): List<StoredFile> {
        val out = mutableListOf<StoredFile>()
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return out
        val info = root.optJSONArray("info") ?: return out
        for (i in 0 until info.length()) {
            val files = info.optJSONObject(i)?.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                val name = f.optString("name")
                if (!isPhoto(name)) continue
                out += StoredFile(name, HOTSPOT_BASE + name, f.optInt("size"), f.optString("createtimestr").padStart(20, '0'))
            }
        }
        return out
    }

    /**
     * Wi-Fi Direct list (GET ?custom=1&cmd=3015), dashcam-style XML:
     * <File><NAME>…</NAME><FPATH>A:\DCIM\100HUNTI\…</FPATH><SIZE>…</SIZE><TIME>2026/11/08 19:30:12</TIME>…</File>
     */
    fun parseDirectList(xml: String): List<StoredFile> {
        val out = mutableListOf<StoredFile>()
        val file = Regex("<File>(.*?)</File>", RegexOption.DOT_MATCHES_ALL)
        fun tag(block: String, t: String) = Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim().orEmpty()
        for (m in file.findAll(xml)) {
            val b = m.groupValues[1]
            val name = tag(b, "NAME")
            if (!isPhoto(name)) continue
            out += StoredFile(name, DIRECT_BASE + "DCIM/100HUNTI/" + name, tag(b, "SIZE").toIntOrNull() ?: 0, tag(b, "TIME"))
        }
        return out
    }

    /** Photo count reply (event 0x42): two bytes, high byte first. Null if it isn't one. */
    fun photoCount(payload: ByteArray): Int? =
        if (payload.size < 2) null else ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    const val EVT_PHOTO_COUNT = 0x42

    /**
     * State event (0x45), sent whenever something changes. Byte 0: taking a photo (1) or not (0);
     * then audio, video, volume up/down, nod, shake, music, worn (GlassX "SyncValueBean").
     */
    const val EVT_STATE = 0x45

    /** True when a state event says the headset is busy taking a photo. */
    fun takingPhoto(payload: ByteArray): Boolean = payload.isNotEmpty() && payload[0].toInt() == 1

    /** Delete one photo from the headset after downloading it, the way GlassX does. */
    fun deleteUrl(file: StoredFile, hotspot: Boolean): String =
        if (hotspot) HOTSPOT_BASE + "app/deletefile?file=" + file.name
        else DIRECT_BASE + "?custom=1&cmd=4003&str=A:\\DCIM\\100HUNTI\\" + file.name

    /** The newest photo on the headset. */
    fun newest(files: List<StoredFile>): StoredFile? = files.maxByOrNull { it.sortKey }

    /** The photo that wasn't there before, or (first time) simply the newest. */
    fun newestNew(now: List<StoredFile>, before: Set<String>): StoredFile? {
        val fresh = now.filter { it.name !in before }
        return (fresh.ifEmpty { if (before.isEmpty()) now else emptyList() }).maxByOrNull { it.sortKey }
    }

    private fun isPhoto(name: String) = name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }

    class Event(val cmd: Int, val payload: ByteArray)

    /** Event packet from the headset: AC 55 len(2) cmd payload… crc. Null if it isn't one. */
    fun event(p: ByteArray): Event? {
        if (p.size < 6 || p[0] != 0xAC.toByte() || p[1] != 0x55.toByte()) return null
        return Event(p[4].toInt() and 0xFF, p.copyOfRange(5, p.size - 1))
    }

    class PhotoPacket(val type: Int, val payload: ByteArray)

    /** Photo-stream packet: 52 58 len(2) type payload… crc 58 52. Null if it isn't one. */
    fun photoPacket(p: ByteArray): PhotoPacket? {
        if (p.size < 8) return null
        if (p[0] != 0x52.toByte() || p[1] != 0x58.toByte()) return null
        if (p[p.size - 2] != 0x58.toByte() || p[p.size - 1] != 0x52.toByte()) return null
        return PhotoPacket(p[4].toInt() and 0xFF, p.copyOfRange(5, p.size - 3))
    }

    sealed class Step {
        data object More : Step()
        class Done(val jpeg: ByteArray) : Step()
        class Failed(val why: String) : Step()
    }

    /**
     * Puts one photo back together. Each data packet starts with the byte offset of its piece
     * (4 bytes, big-endian), then the JPEG bytes. A jump in the offset means a piece went missing.
     */
    class Assembler {
        private val buf = ByteArrayOutputStream()
        private var checkOffsets = true
        private var started = false

        val size: Int get() = buf.size()

        fun reset() {
            buf.reset()
            checkOffsets = true
            started = false
        }

        fun feed(raw: ByteArray): Step {
            val p = photoPacket(raw) ?: return Step.More
            when (p.type) {
                PHOTO_START -> reset()
                PHOTO_DATA -> {
                    if (p.payload.size < 4) return Step.More
                    val off = ((p.payload[0].toInt() and 0xFF) shl 24) or
                        ((p.payload[1].toInt() and 0xFF) shl 16) or
                        ((p.payload[2].toInt() and 0xFF) shl 8) or
                        (p.payload[3].toInt() and 0xFF)
                    if (!started) {
                        started = true
                        // If the very first piece doesn't say 0, these 4 bytes mean something else
                        // on this firmware: stop checking rather than reject every photo.
                        if (off != 0) checkOffsets = false
                    }
                    if (checkOffsets && off != buf.size()) {
                        return Step.Failed("data went missing at ${buf.size() / 1024} KB")
                    }
                    buf.write(p.payload, 4, p.payload.size - 4)
                }
                PHOTO_END -> {
                    val jpeg = buf.toByteArray()
                    reset()
                    return when {
                        jpeg.isEmpty() -> Step.Failed("the headset sent an empty photo")
                        !looksLikeJpeg(jpeg) -> Step.Failed("the photo arrived damaged")
                        else -> Step.Done(jpeg)
                    }
                }
            }
            return Step.More
        }
    }

    fun looksLikeJpeg(b: ByteArray): Boolean =
        b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
}
