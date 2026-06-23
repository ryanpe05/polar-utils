package com.polarutils.rrlogger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Minimal UI: Start / Stop capture, and Export to share the recorded CSV files.
 * The actual BLE work runs in [RrService] so it survives the screen turning off.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var fileList: ListView
    private var files: List<File> = emptyList()

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
    ) { startCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        fileList = findViewById(R.id.file_list)

        findViewById<Button>(R.id.start).setOnClickListener { ensurePermissionsThenStart() }
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

    private fun ensurePermissionsThenStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCapture()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startCapture() {
        val granted = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            Toast.makeText(this, "Permissions required to scan/connect", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, RrService::class.java).apply { action = RrService.ACTION_START }
        ContextCompat.startForegroundService(this, intent)
        statusView.text = "Starting…"
    }

    private fun stopCapture() {
        val intent = Intent(this, RrService::class.java).apply { action = RrService.ACTION_STOP }
        startService(intent)
        statusView.text = "Stopped"
        fileList.postDelayed({ refreshFiles() }, 500)
    }

    private fun refreshFiles() {
        files = RrService.dataDir(this)
            .listFiles { f -> f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        val labels = files.map { "${it.name}  (${it.length() / 1024} KB)" }
        fileList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    /** Share all recordings (or the tapped one) via the system share sheet. */
    private fun exportFiles() {
        refreshFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "No recordings yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>(files.map {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
        })
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Export RR CSV"))
    }
}
