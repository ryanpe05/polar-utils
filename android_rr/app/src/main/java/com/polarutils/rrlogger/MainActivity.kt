package com.polarutils.rrlogger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Minimal UI: pick a strap to pair with, Start / Stop capture, and Export to
 * share selected recorded CSV files. The actual BLE capture runs in [RrService]
 * so it survives the screen turning off.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var fileList: ListView
    private var files: List<File> = emptyList()

    // ---- device discovery (strap picker) ----
    private var scanner: BluetoothLeScanner? = null
    /** Straps discovered during the current scan, keyed by MAC address (insertion-ordered). */
    private val discovered = LinkedHashMap<String, Discovered>()
    private var scanDialog: AlertDialog? = null
    private var scanAdapter: ArrayAdapter<String>? = null
    private var scanning = false

    private data class Discovered(val device: BluetoothDevice, val name: String?, val rssi: Int)

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(RrService.EXTRA_STATUS) ?: return
            val hr = intent.getIntExtra(RrService.EXTRA_HR, -1)
            val count = intent.getIntExtra(RrService.EXTRA_RR_COUNT, -1)
            statusView.text = buildString {
                append(status)
                if (hr >= 0) append("\nHR: $hr bpm")
                if (count >= 0) append("   RR intervals: $count")
                intent.getStringExtra(RrService.EXTRA_FILE)?.let { append("\nFile: $it") }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startDeviceScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        fileList = findViewById(R.id.file_list)

        findViewById<Button>(R.id.start).setOnClickListener { ensurePermissionsThenScan() }
        findViewById<Button>(R.id.stop).setOnClickListener { stopCapture() }
        findViewById<Button>(R.id.export).setOnClickListener { exportFiles() }

        statusView.text = "Idle"
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(RrService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        refreshFiles()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        stopDeviceScan()
        scanDialog?.dismiss()
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsThenScan() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startDeviceScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---- strap picker ----

    /** Show the picker (creating it if needed) and start scanning for HR straps. */
    private fun startDeviceScan() {
        if (!hasPermissions()) {
            Toast.makeText(this, "Permissions required to scan/connect", Toast.LENGTH_LONG).show()
            return
        }
        if (scanDialog?.isShowing != true) showScanDialog()
        beginScan()
    }

    /** (Re)start the BLE scan, reusing the currently-shown picker dialog. */
    @SuppressLint("MissingPermission")
    private fun beginScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is off", Toast.LENGTH_LONG).show()
            scanDialog?.setTitle("Bluetooth is off")
            return
        }

        stopDeviceScan()
        discovered.clear()
        scanAdapter?.clear()
        scanAdapter?.notifyDataSetChanged()
        scanDialog?.setTitle("Searching for straps…")

        scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(RrService.HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        scanner?.startScan(listOf(filter), settings, deviceScanCallback)

        // Give up the scan after a while so the radio isn't left running.
        fileList.postDelayed({
            if (scanning) {
                stopDeviceScan()
                scanDialog?.setTitle(
                    if (discovered.isEmpty()) "No straps found — tap Rescan"
                    else "Select a strap (${discovered.size} found)"
                )
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val deviceScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return
            val name = try { device.name } catch (_: SecurityException) { null }
            // Newer sightings refresh the RSSI; keep the map insertion-ordered.
            discovered[address] = Discovered(device, name, result.rssi)
            updateScanDialog()
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            runOnUiThread {
                scanDialog?.setTitle("Scan failed ($errorCode)")
            }
        }
    }

    private fun showScanDialog() {
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        scanAdapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle("Searching for straps…")
            .setAdapter(adapter) { _, which ->
                discovered.values.toList().getOrNull(which)?.let { chosen ->
                    stopDeviceScan()
                    startCapture(chosen.device.address)
                }
            }
            .setNegativeButton("Cancel") { d, _ -> stopDeviceScan(); d.dismiss() }
            .setPositiveButton("Rescan", null) // overridden below so it doesn't dismiss the dialog
            .setOnDismissListener { stopDeviceScan() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { beginScan() }
        }
        scanDialog = dialog
        dialog.show()
    }

    private fun updateScanDialog() {
        val adapter = scanAdapter ?: return
        runOnUiThread {
            adapter.clear()
            adapter.addAll(discovered.values.map { labelFor(it) })
            adapter.notifyDataSetChanged()
            if (scanning) scanDialog?.setTitle("Select a strap (${discovered.size} found)")
        }
    }

    private fun labelFor(d: Discovered): String {
        val title = d.name ?: "(unknown)"
        return "$title\n${d.device.address}   ${d.rssi} dBm"
    }

    @SuppressLint("MissingPermission")
    private fun stopDeviceScan() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(deviceScanCallback) } catch (_: Exception) {}
    }

    // ---- capture control ----

    private fun startCapture(address: String?) {
        if (!hasPermissions()) {
            Toast.makeText(this, "Permissions required to scan/connect", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, RrService::class.java).apply {
            action = RrService.ACTION_START
            address?.let { putExtra(RrService.EXTRA_DEVICE_ADDRESS, it) }
        }
        ContextCompat.startForegroundService(this, intent)
        statusView.text = "Starting…"
    }

    private fun stopCapture() {
        val intent = Intent(this, RrService::class.java).apply { action = RrService.ACTION_STOP }
        startService(intent)
        statusView.text = "Stopped"
        fileList.postDelayed({ refreshFiles() }, 500)
    }

    // ---- recordings + export ----

    private fun refreshFiles() {
        files = RrService.dataDir(this)
            .listFiles { f -> f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        val labels = files.map { "${it.name}  (${it.length() / 1024} KB)" }
        fileList.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        fileList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
    }

    /** Files whose checkbox is currently ticked in the recordings list. */
    private fun selectedFiles(): List<File> {
        val checked = fileList.checkedItemPositions ?: return emptyList()
        // Preserve the list's display order (newest first) rather than sparse-array order.
        return files.indices.filter { checked.get(it, false) }.map { files[it] }
    }

    /** Share the ticked recordings; if none are ticked, offer to export all. */
    private fun exportFiles() {
        if (files.isEmpty()) {
            Toast.makeText(this, "No recordings yet", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = selectedFiles()
        if (selected.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Nothing selected")
                .setMessage("Tick the recordings you want to export, or export all ${files.size}?")
                .setPositiveButton("Export all") { _, _ -> shareFiles(files) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        shareFiles(selected)
    }

    private fun shareFiles(toExport: List<File>) {
        val uris = ArrayList<Uri>(toExport.map {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
        })
        val share = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/csv"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(share, "Export RR CSV"))
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 10_000L
    }
}
