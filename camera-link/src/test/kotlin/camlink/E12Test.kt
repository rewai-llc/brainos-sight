package camlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E12Test {
    private fun hex(s: String): ByteArray =
        s.split('-', ' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    /** Wraps a payload the way the headset does on the photo channel: 52 58 len type payload crc 58 52. */
    private fun photoPacket(type: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        val crc = (type + payload.sumOf { it.toInt() and 0xFF }) and 0xFF
        return byteArrayOf(0x52, 0x58, (len shr 8).toByte(), len.toByte(), type.toByte()) +
            payload + byteArrayOf(crc.toByte(), 0x58, 0x52)
    }

    private fun data(offset: Int, bytes: ByteArray) = photoPacket(
        E12.PHOTO_DATA,
        byteArrayOf((offset shr 24).toByte(), (offset shr 16).toByte(), (offset shr 8).toByte(), offset.toByte()) + bytes,
    )

    @Test
    fun takePhotoIsTheCommandThatWorkedOnTheRealHeadset() {
        assertArrayEquals(hex("AB-55-00-03-22-31-53"), E12.takePhoto())
    }

    @Test
    fun parsesTheRealStatusEventAndEndMarker() {
        val status = E12.event(hex("AC-55-00-0C-45-00-00-00-00-00-00-00-00-00-00-45"))
        assertNotNull(status)
        assertEquals(0x45, status!!.cmd)
        assertNull(E12.event(hex("AB-55-00-03-22-31-53"))) // our own command isn't an event

        val end = E12.photoPacket(hex("52-58-00-03-99-00-99-58-52"))
        assertNotNull(end)
        assertEquals(E12.PHOTO_END, end!!.type)
        assertArrayEquals(byteArrayOf(0), end.payload)
    }

    @Test
    fun reassemblesAPhotoFromPieces() {
        val a = E12.Assembler()
        val first = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(494) { 1 }
        val second = ByteArray(200) { 2 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertEquals(E12.Step.More, a.feed(photoPacket(E12.PHOTO_START, byteArrayOf(0))))
        assertEquals(E12.Step.More, a.feed(data(0, first)))
        assertEquals(E12.Step.More, a.feed(data(496, second)))
        assertEquals(698, a.size)
        val done = a.feed(hex("52-58-00-03-99-00-99-58-52"))
        assertTrue(done is E12.Step.Done)
        assertArrayEquals(first + second, (done as E12.Step.Done).jpeg)
        assertEquals(0, a.size)
    }

    @Test
    fun aMissingPieceFailsThePhoto() {
        val a = E12.Assembler()
        a.feed(data(0, byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(494)))
        val step = a.feed(data(992, ByteArray(100))) // the piece at 496 never arrived
        assertTrue(step is E12.Step.Failed)
    }

    @Test
    fun anEmptyOrDamagedPhotoIsReportedNotReturned() {
        val a = E12.Assembler()
        assertTrue(a.feed(hex("52-58-00-03-99-00-99-58-52")) is E12.Step.Failed)
        a.feed(data(0, ByteArray(50) { 7 }))
        assertTrue(a.feed(hex("52-58-00-03-99-00-99-58-52")) is E12.Step.Failed)
    }

    @Test
    fun ifTheFirstOffsetIsntZeroOffsetsAreIgnored() {
        val a = E12.Assembler()
        a.feed(data(0x3450, byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(10)))
        a.feed(data(0x9999, ByteArray(10)))
        assertTrue(a.feed(hex("52-58-00-03-99-00-99-58-52")) is E12.Step.Done)
    }

    @Test
    fun wifiCommandsMatchTheApp() {
        assertArrayEquals(hex("AB-55-00-02-64-64"), E12.getCustomer())
        assertArrayEquals(hex("AB-55-00-03-39-30-69"), E12.getWifiInfo(hotspot = true))
        assertArrayEquals(hex("AB-55-00-03-39-31-6A"), E12.getWifiInfo(hotspot = false))
        assertArrayEquals(hex("AB-55-00-03-22-30-52"), E12.takeStoredPhoto())
    }

    @Test
    fun readsWifiModeAndName() {
        assertEquals(true, E12.hotspotModeFromCustomer("T001A123".toByteArray()))
        assertEquals(false, E12.hotspotModeFromCustomer("S002A123".toByteArray()))
        assertNull(E12.hotspotModeFromCustomer(ByteArray(3)))
        assertEquals("E12-CC60_WIFI", E12.wifiName("E12-CC60_WIFI\u0000".toByteArray()))
        assertNull(E12.wifiName("ab".toByteArray()))
    }

    @Test
    fun parsesTheHotspotPhotoListAndPicksTheNewOne() {
        val json = """{"result":0,"info":[{"folder":"photo","count":3,"files":[
            {"name":"/photo/IMG_0001.JPG","createtimestr":"20261108193000","size":2100000,"type":1},
            {"name":"/video/VID_0002.MP4","createtimestr":"20261108193100","size":9000000,"type":2},
            {"name":"/photo/IMG_0003.JPG","createtimestr":"20261108193200","size":2200000,"type":1}]}]}"""
        val files = E12.parseHotspotList(json)
        assertEquals(2, files.size) // the video is left out
        assertEquals("http://192.168.169.1//photo/IMG_0003.JPG", E12.newestNew(files, emptySet())!!.url)
        assertNull(E12.newestNew(files, files.map { it.name }.toSet())) // nothing new yet
        assertEquals("/photo/IMG_0003.JPG", E12.newestNew(files, setOf("/photo/IMG_0001.JPG"))!!.name)
        assertTrue(E12.parseHotspotList("not json").isEmpty())
    }

    @Test
    fun readsThePhotoCountAndBuildsDeleteUrls() {
        assertEquals(2, E12.photoCount(hex("00-02"))) // seen on the real headset: 0x42 0002
        assertEquals(258, E12.photoCount(hex("01-02")))
        assertNull(E12.photoCount(hex("05")))
        val f = E12.StoredFile("/photo/IMG_0003.JPG", "u", 1, "1")
        assertEquals("http://192.168.169.1/app/deletefile?file=/photo/IMG_0003.JPG", E12.deleteUrl(f, hotspot = true))
        assertEquals("DH-TwI-A79114", E12.wifiName(hex("44-48-2D-54-77-49-2D-41-37-39-31-31-34"))) // real reply
    }

    @Test
    fun readsThePhotoBusyState() {
        // Seen on the real headset right after a take-photo command, then about 3 s later.
        val busy = E12.event(hex("AC-55-00-0C-45-01-00-00-00-00-00-00-00-00-00-46"))!!
        val done = E12.event(hex("AC-55-00-0C-45-00-00-00-00-00-00-00-00-00-00-45"))!!
        assertEquals(E12.EVT_STATE, busy.cmd)
        assertTrue(E12.takingPhoto(busy.payload))
        assertTrue(!E12.takingPhoto(done.payload))
    }

    @Test
    fun parsesTheWifiDirectPhotoList() {
        val xml = """<LIST><ALLFile><File><NAME>2026_1108_193000_001.JPG</NAME><FPATH>A:\DCIM\100HUNTI\2026_1108_193000_001.JPG</FPATH>
            <SIZE>2100000</SIZE><TIMECODE>1</TIMECODE><TIME>2026/11/08 19:30:00</TIME><ATTR>32</ATTR></File>
            <File><NAME>2026_1108_193100_002.MOV</NAME><SIZE>9</SIZE><TIME>2026/11/08 19:31:00</TIME></File></ALLFile></LIST>"""
        val files = E12.parseDirectList(xml)
        assertEquals(1, files.size)
        assertEquals("http://192.168.49.207/DCIM/100HUNTI/2026_1108_193000_001.JPG", files[0].url)
    }

    @Test
    fun ignoresPacketsThatArentPhotoPackets() {
        val a = E12.Assembler()
        assertEquals(E12.Step.More, a.feed(hex("AC-55-00-03-45-00-45")))
        assertEquals(E12.Step.More, a.feed(ByteArray(3)))
        assertEquals(0, a.size)
    }
}
