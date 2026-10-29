package sight

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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bluetooth LE link to the BrainOS Cam (XIAO ESP32S3 Sense running camera/brainos_cam).
 * Finds the camera by its service UUID, keeps it connected, and fetches one JPEG on [snap].
 * Photos live only in RAM.
 */
@SuppressLint("MissingPermission")
class CameraLink(
    context: Context,
    private val onStatus: (String) -> Unit,
    /** The camera's button was pressed (firmware v3+). Called on a Bluetooth thread. */
    private val onButton: () -> Unit = {},
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var control: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var running = false
    @Volatile private var scanning = false
    @Volatile private var mtu = 23

    // Photo assembly
    private val lock = Any()
    private var pending: CountDownLatch? = null
    private var expected = 0
    private val buf = ByteArrayOutputStream()
    private var result: ByteArray? = null

    val isReady: Boolean get() = ready

    fun start() {
        if (running) return
        running = true
        if (!hasPermissions()) {
            onStatus("needs Bluetooth permission")
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off")
            return
        }
        scan()
    }

    fun stop() {
        running = false
        ready = false
        stopScan()
        main.removeCallbacksAndMessages(null)
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        control = null
        synchronized(lock) {
            buf.reset()
            pending?.countDown()
        }
        onStatus("off")
    }

    /**
     * Take one photo. Blocks up to [timeoutMs]. Returns JPEG bytes or null.
     * Size 4 = 1600x1200, enough to read a menu at arm's length or a sign across the room.
     */
    fun snap(size: Int = 4, timeoutMs: Long = 8_000): ByteArray? {
        repeat(2) { attempt ->
            val jpeg = snapOnce(size, timeoutMs)
            if (jpeg != null) return jpeg
            Log.i(LOG_TAG, "camera-snap-retry attempt=$attempt")
        }
        return null
    }

    private fun snapOnce(size: Int, timeoutMs: Long): ByteArray? {
        if (!ready) return null
        val latch = CountDownLatch(1)
        synchronized(lock) {
            buf.reset()
            expected = 0
            result = null
            pending = latch
        }
        val chunk = (mtu - 3).coerceIn(20, 509)
        if (!write(byteArrayOf(0x01, size.toByte(), (chunk and 0xFF).toByte(), (chunk shr 8).toByte()))) return null
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            pending = null
            return result
        }
    }

    /** Someone started talking: power the camera up so a photo is instant if needed. */
    fun warm() {
        if (ready) write(byteArrayOf(0x02))
    }

    fun setOrientation(vflip: Boolean, hmirror: Boolean) {
        if (ready) write(byteArrayOf(0x03, if (vflip) 1 else 0, if (hmirror) 1 else 0))
    }

    // ---------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running || gatt != null) return
            stopScan()
            connect(result.device)
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
        onStatus("searching")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            // Scanning is power-hungry: pause after 30 s and try again later.
            main.postDelayed({
                if (scanning) {
                    stopScan()
                    retryLater()
                }
            }, 30_000)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "camera-scan-failed " + t.javaClass.simpleName)
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

    private fun connect(device: BluetoothDevice) {
        onStatus("connecting")
        gatt = device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && running) {
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                control = null
                runCatching { g.close() }
                if (gatt === g) gatt = null
                synchronized(lock) { pending?.countDown() }
                if (running) {
                    onStatus("searching")
                    main.postDelayed({ scan() }, 1_500)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            val ctl = svc?.getCharacteristic(CONTROL)
            val dat = svc?.getCharacteristic(DATA)
            if (ctl == null || dat == null) {
                onStatus("not a BrainOS camera")
                g.disconnect()
                return
            }
            control = ctl
            g.setCharacteristicNotification(dat, true)
            val cccd = dat.getDescriptor(CCCD) ?: run {
                ready = true
                onStatus("connected")
                return
            }
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

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD) {
                ready = status == BluetoothGatt.GATT_SUCCESS
                onStatus(if (ready) "connected" else "setup failed")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == DATA) onPacket(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == DATA) onPacket(c.value ?: return)
        }
    }

    private fun write(value: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = control ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    /** Packet format documented in camera/brainos_cam/brainos_cam.ino. */
    private fun onPacket(p: ByteArray) {
        if (p.isEmpty()) return
        if (p[0].toInt() == 0x05) {
            onButton()
            return
        }
        synchronized(lock) {
            val latch = pending ?: return
            when (p[0].toInt()) {
                0x01 -> if (p.size >= 5) {
                    expected = u32(p, 1)
                    buf.reset()
                }
                0x02 -> if (p.size > 5 && expected > 0) {
                    val off = u32(p, 1)
                    if (off != buf.size()) {
                        // A notification went missing: fail this photo (caller retries once).
                        Log.w(LOG_TAG, "camera-gap expected=${buf.size()} got=$off")
                        expected = 0
                        result = null
                        latch.countDown()
                        return
                    }
                    buf.write(p, 5, p.size - 5)
                }
                0x03 -> {
                    result = if (expected > 0 && buf.size() == expected) buf.toByteArray() else null
                    buf.reset()
                    latch.countDown()
                }
                0x04 -> {
                    Log.w(LOG_TAG, "camera-error code=" + (p.getOrNull(1)?.toInt() ?: -1))
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

        fun u32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or
                ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or
                ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}
