package com.polarutils.rrlogger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Foreground service that connects to a BLE heart-rate strap (e.g. Polar H9),
 * subscribes to RR-interval notifications, and appends them to a CSV file in
 * the app's external files dir. Runs as a connectedDevice foreground service so
 * capture continues during a ride with the screen off.
 */
class RrService : Service() {

    companion object {
        const val ACTION_START = "com.polarutils.rrlogger.START"
        const val ACTION_STOP = "com.polarutils.rrlogger.STOP"

        // Broadcast back to the UI with live status.
        const val ACTION_STATUS = "com.polarutils.rrlogger.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_HR = "hr"
        const val EXTRA_RR_COUNT = "rr_count"
        const val EXTRA_FILE = "file"

        private const val CHANNEL_ID = "rr_capture"
        private const val NOTIF_ID = 1

        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Directory where recordings are stored. */
        fun dataDir(context: Context): File =
            File(context.getExternalFilesDir(null), "rr_data").apply { mkdirs() }
    }

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writer: FileWriter? = null
    private var csvFile: File? = null

    private var cumRrMs = 0.0
    private var rrCount = 0
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (running) return
        running = true

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))

        // Open a fresh CSV for this session.
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            .withZone(ZoneOffset.systemDefault())
            .format(Instant.now())
        csvFile = File(dataDir(this), "${stamp}_rr.csv")
        writer = FileWriter(csvFile, true).apply {
            append("iso_time,hr_bpm,rr_ms,cum_rr_ms\n")
            flush()
        }
        cumRrMs = 0.0
        rrCount = 0

        broadcast("Scanning for HR strap…")
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            broadcast("Bluetooth is off")
            return
        }
        scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // First strap wins; stop scanning and connect.
            scanner?.stopScan(this)
            val device = result.device
            broadcast("Connecting to ${device.name ?: device.address}…")
            gatt = device.connectGatt(this@RrService, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            broadcast("Scan failed ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcast("Connected — discovering services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                broadcast("Disconnected")
                if (running) {
                    // Strap dropped out (out of range, etc.) — try to reconnect.
                    startScan()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_UUID)
            if (ch == null) {
                broadcast("HR characteristic not found")
                return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
            broadcast("Recording")
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleMeasurement(value)
        }

        // Pre-13
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleMeasurement(ch.value ?: return)
        }
    }

    private fun handleMeasurement(data: ByteArray) {
        val now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.now().atOffset(ZoneOffset.UTC)
        )
        val m = HrParser.parse(data)
        val w = writer ?: return
        try {
            if (m.rrMs.isEmpty()) {
                // Keep an HR-only row so the timeline is never blank.
                w.append("$now,${m.hr},,\n")
            } else {
                for (rr in m.rrMs) {
                    cumRrMs += rr
                    rrCount++
                    val cum = Math.round(cumRrMs * 100.0) / 100.0
                    w.append("$now,${m.hr},$rr,$cum\n")
                }
            }
            w.flush()
        } catch (_: Exception) {
            // Best-effort logging; ignore transient IO errors during a ride.
        }
        updateNotification("HR ${m.hr} bpm · $rrCount RR")
        broadcastData(m.hr, rrCount)
    }

    @SuppressLint("MissingPermission")
    private fun stopCapture() {
        running = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
        broadcast("Stopped")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ---- notifications ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "RR capture", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("RR Logger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ---- status broadcasts to the UI ----

    private fun broadcast(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_FILE, csvFile?.name)
        })
    }

    private fun broadcastData(hr: Int, count: Int) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, "Recording")
            putExtra(EXTRA_HR, hr)
            putExtra(EXTRA_RR_COUNT, count)
            putExtra(EXTRA_FILE, csvFile?.name)
        })
    }
}
