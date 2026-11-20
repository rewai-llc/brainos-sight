package camlink

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing between a phone app and the BrainOS Cam (firmware v7+), so a stranger's phone can't
 * connect to the camera and pull photos. See "Pairing" in camera/PROTOCOL.md.
 *
 * Each app that pairs gets its own 16-byte key, stored by the camera (up to four) and by the app.
 * On every connection the camera sends a fresh random number and the app proves it knows the key
 * with HMAC-SHA256 over that number. The key itself is only ever sent once, while pairing.
 * Pure logic, no Android, so it is unit-tested.
 */
object CamAuth {
    const val HELLO = 0x10
    const val PROVE = 0x11
    const val CLAIM = 0x12

    const val KEY_BYTES = 16
    const val NONCE_BYTES = 16
    const val MAC_BYTES = 16

    /** What the camera says about itself when greeted. */
    class Hello(val claimed: Boolean, val pairingOpen: Boolean, val version: Int, val nonce: ByteArray)

    fun hello(): ByteArray = byteArrayOf(HELLO.toByte())

    /** Camera reply: 10 flags version nonce[16]. flags bit 0 = already paired with someone, bit 1 = pairing is open. */
    fun parseHello(p: ByteArray): Hello? {
        if (p.size < 3 + NONCE_BYTES || p[0].toInt() != HELLO) return null
        val flags = p[1].toInt()
        return Hello(
            claimed = flags and 1 != 0,
            pairingOpen = flags and 2 != 0,
            version = p[2].toInt() and 0xFF,
            nonce = p.copyOfRange(3, 3 + NONCE_BYTES),
        )
    }

    /** "I know the key": 11 mac[16], where mac = first 16 bytes of HMAC-SHA256(key, nonce). */
    fun prove(key: ByteArray, nonce: ByteArray): ByteArray = byteArrayOf(PROVE.toByte()) + mac(key, nonce)

    /** "Remember this key for me": 12 key[16]. Only accepted while the camera is unpaired or pairing is open. */
    fun claim(key: ByteArray): ByteArray = byteArrayOf(CLAIM.toByte()) + key

    /** Camera verdict: 11 01 = accepted, 11 00 = refused. */
    fun accepted(p: ByteArray?): Boolean = p != null && p.size >= 2 && p[0].toInt() == PROVE && p[1].toInt() == 1

    fun mac(key: ByteArray, nonce: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(nonce).copyOf(MAC_BYTES)
    }

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
}
