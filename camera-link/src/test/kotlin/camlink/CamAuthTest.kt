package camlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamAuthTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun macMatchesAReferenceValue() {
        // HMAC-SHA256(key = 00..0F, message = 20..2F), first 16 bytes. The firmware must produce the same.
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(16) { (0x20 + it).toByte() }
        assertArrayEquals(hex("ed23e950de666b7accae4974290e96cb"), CamAuth.mac(key, nonce))
    }

    @Test fun packetsHaveTheDocumentedShape() {
        val key = ByteArray(16) { 7 }
        val nonce = ByteArray(16) { 9 }
        assertArrayEquals(byteArrayOf(0x10), CamAuth.hello())
        val prove = CamAuth.prove(key, nonce)
        assertEquals(17, prove.size)
        assertEquals(0x11, prove[0].toInt())
        val claim = CamAuth.claim(key)
        assertEquals(17, claim.size)
        assertEquals(0x12, claim[0].toInt())
        assertArrayEquals(key, claim.copyOfRange(1, 17))
    }

    @Test fun parsesTheCameraGreeting() {
        val nonce = ByteArray(16) { (it * 3).toByte() }
        val h = CamAuth.parseHello(byteArrayOf(0x10, 0x03, 7) + nonce)!!
        assertTrue(h.claimed)
        assertTrue(h.pairingOpen)
        assertEquals(7, h.version)
        assertArrayEquals(nonce, h.nonce)
        val fresh = CamAuth.parseHello(byteArrayOf(0x10, 0x00, 7) + nonce)!!
        assertFalse(fresh.claimed)
        assertFalse(fresh.pairingOpen)
        assertNull(CamAuth.parseHello(byteArrayOf(0x10, 0x01, 7, 1, 2, 3))) // too short
        assertNull(CamAuth.parseHello(byteArrayOf(0x05)))
    }

    @Test fun onlyAnExplicitYesCounts() {
        assertTrue(CamAuth.accepted(byteArrayOf(0x11, 1)))
        assertFalse(CamAuth.accepted(byteArrayOf(0x11, 0)))
        assertFalse(CamAuth.accepted(byteArrayOf(0x11)))
        assertFalse(CamAuth.accepted(null))
    }

    @Test fun cameraPacketOffsetsDecodeLittleEndian() {
        assertEquals(0x04030201, CameraLink.u32(byteArrayOf(9, 1, 2, 3, 4), 1))
        assertEquals(40_000, CameraLink.u32(byteArrayOf(0x40, (0x9C).toByte(), 0, 0), 0))
    }

    @Test fun keysAreRandom() {
        assertEquals(16, CamAuth.newKey().size)
        assertFalse(CamAuth.newKey().contentEquals(CamAuth.newKey()))
    }
}
