package camlink

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A private link to the E12 headset's own Wi-Fi, used only to download full-size photos from it.
 *
 * Hotspot mode: the phone joins the headset's network as a local-only connection (no internet
 * capability), and only our requests go over it, so the phone keeps its normal internet.
 * Wi-Fi Direct mode: the phone joins the headset's Wi-Fi Direct group.
 * The only addresses ever contacted are the headset's own ([E12.HOTSPOT_BASE], [E12.DIRECT_BASE]).
 * Call from a background thread.
 */
@SuppressLint("MissingPermission")
class E12Wifi(context: Context, private val tag: String) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    @Volatile private var network: Network? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var p2p: WifiP2pManager? = null
    @Volatile private var p2pChannel: WifiP2pManager.Channel? = null
    @Volatile private var hotspot = true
    @Volatile var connectedTo: String? = null
        private set

    val isConnected: Boolean get() = connectedTo != null

    /** Joins the headset's network. Returns null when connected, or why it failed. */
    fun connect(ssid: String, password: String, hotspotMode: Boolean, timeoutMs: Long = 90_000): String? {
        if (connectedTo == ssid) return null
        disconnect()
        hotspot = hotspotMode
        return if (hotspotMode) connectHotspot(ssid, password, timeoutMs) else connectDirect(ssid, password, timeoutMs)
    }

    private fun connectHotspot(ssid: String, password: String, timeoutMs: Long): String? {
        if (Build.VERSION.SDK_INT < 29) return "needs Android 10 or newer"
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // local only: never the internet
            .setNetworkSpecifier(spec)
            .build()
        val got = CountDownLatch(1)
        var why: String? = "the headset's Wi-Fi didn't answer"
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                connectedTo = ssid
                why = null
                Log.i(tag, "e12-wifi hotspot connected")
                got.countDown()
            }

            override fun onUnavailable() {
                why = "couldn't join the headset's Wi-Fi"
                got.countDown()
            }

            override fun onLost(n: Network) {
                if (n == network) {
                    network = null
                    connectedTo = null
                    Log.i(tag, "e12-wifi hotspot lost")
                }
            }
        }
        callback = cb
        return try {
            // No system timeout: Android may show a "Connect to …?" prompt the first time, and the
            // person needs time to tap it. We give up ourselves after [timeoutMs].
            cm.requestNetwork(request, cb)
            if (!got.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                runCatching { cm.unregisterNetworkCallback(cb) }
                callback = null
                why = "the headset's Wi-Fi wasn't joined (was the Connect prompt tapped?)"
            }
            why
        } catch (t: Throwable) {
            Log.w(tag, "e12-wifi hotspot failed " + t.javaClass.simpleName + ": " + t.message)
            "Wi-Fi permission missing or Wi-Fi is off"
        }
    }

    private fun connectDirect(ssid: String, password: String, timeoutMs: Long): String? {
        if (Build.VERSION.SDK_INT < 29) return "needs Android 10 or newer"
        val mgr = app.getSystemService(WifiP2pManager::class.java) ?: return "this phone has no Wi-Fi Direct"
        val ch = mgr.initialize(app, Looper.getMainLooper(), null) ?: return "Wi-Fi Direct unavailable"
        p2p = mgr
        p2pChannel = ch
        val got = CountDownLatch(1)
        var why: String? = "the headset's Wi-Fi Direct didn't answer"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                mgr.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                    if (info?.groupFormed == true) {
                        connectedTo = ssid
                        why = null
                        Log.i(tag, "e12-wifi direct connected owner=${info.groupOwnerAddress}")
                        got.countDown()
                    }
                }
            }
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(receiver, filter)
        return try {
            val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(password).build()
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) {
                    why = "Wi-Fi Direct refused ($reason)"
                    got.countDown()
                }
            })
            got.await(timeoutMs, TimeUnit.MILLISECONDS)
            why
        } catch (t: Throwable) {
            Log.w(tag, "e12-wifi direct failed " + t.javaClass.simpleName + ": " + t.message)
            "Wi-Fi permission missing or Wi-Fi is off"
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    /** GET a URL on the headset. Returns the body, or null. Refuses any address that isn't the headset's. */
    fun get(url: String, timeoutMs: Int = 15_000, maxBytes: Int = MAX_BODY_BYTES): ByteArray? {
        if (!url.startsWith(E12.HOTSPOT_BASE) && !url.startsWith(E12.DIRECT_BASE)) return null
        if (!isConnected) return null
        return try {
            val u = URL(url)
            val conn = (if (hotspot) network?.openConnection(u) else u.openConnection()) as? HttpURLConnection ?: return null
            conn.connectTimeout = 5_000
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            try {
                if (conn.responseCode != 200) {
                    Log.w(tag, "e12-wifi http ${conn.responseCode} for ${u.path}")
                    return null
                }
                // Never trust the size: anything on that network could answer, so stop at a sane limit.
                if (conn.contentLengthLong > maxBytes) {
                    Log.w(tag, "e12-wifi body too large for ${u.path}")
                    return null
                }
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() + n > maxBytes) {
                            Log.w(tag, "e12-wifi body too large for ${u.path}")
                            return null
                        }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(tag, "e12-wifi get failed " + t.javaClass.simpleName + ": " + t.message)
            null
        }
    }

    companion object {
        /** Largest reply accepted from the headset (a full-size photo is 1 to 3 MB). */
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
    }

    fun disconnect() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        val mgr = p2p
        val ch = p2pChannel
        if (mgr != null && ch != null) runCatching { mgr.removeGroup(ch, null) }
        p2p = null
        p2pChannel = null
        if (connectedTo != null) Log.i(tag, "e12-wifi disconnected")
        connectedTo = null
    }
}
