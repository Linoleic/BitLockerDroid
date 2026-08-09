package com.bitlockerdroid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bitlockerdroid.R
import com.bitlockerdroid.service.DetectedVolume
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.util.LogFile

/**
 * Central management screen: lists unlocked BitLocker volumes and allows
 * locking them, plus a diagnostic log viewer. Also shows volumes detected on
 * the bus that are still locked, so the user can unlock them manually even when
 * no popup fired (e.g. after a reformat/re-encrypt the system hook remembers the
 * node and suppresses the broadcast).
 */
class BitLockerSettingsActivity : Activity() {

    private lateinit var volumeList: ListView
    private lateinit var emptyText: TextView
    private lateinit var adapter: VolumeAdapter

    private lateinit var detectedList: ListView
    private lateinit var detectedHeader: TextView
    private lateinit var detectedEmpty: TextView
    private lateinit var detectedAdapter: DetectedAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Request notification permission (Android 13+) so the unlock prompt
        // notification can be shown.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }

        // Ensure the core service (with its periodic scan) is running.
        try {
            startService(
                android.content.Intent(this, com.bitlockerdroid.service.BitLockerCoreService::class.java)
            )
        } catch (e: Exception) {
            // ignore
        }

        volumeList = findViewById(R.id.volume_list)
        emptyText = findViewById(R.id.empty_text)
        adapter = VolumeAdapter(this)
        volumeList.adapter = adapter

        detectedList = findViewById(R.id.detected_list)
        detectedHeader = findViewById(R.id.detected_header)
        detectedEmpty = findViewById(R.id.detected_empty)
        detectedAdapter = DetectedAdapter(this)
        detectedList.adapter = detectedAdapter

        findViewById<Button>(R.id.refresh).setOnClickListener {
            LogFile.write("app", "refresh clicked")
            refresh()
            Toast.makeText(this, "Refreshed", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.view_log).setOnClickListener { showLogDialog() }

        findViewById<Button>(R.id.scan).setOnClickListener {
            LogFile.write("app", "manual scan triggered")
            startScan(showToast = true)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Runs a detection scan in the background and refreshes the UI when done. */
    private fun startScan(showToast: Boolean) {
        if (showToast) Toast.makeText(this, "Scan started, see log", Toast.LENGTH_SHORT).show()
        Thread {
            val found = com.bitlockerdroid.service.BitLockerDetector.scanAndDetect(this)
            LogFile.write("app", "manual scan result: found=$found")
            runOnUiThread { refresh() }
        }.start()
    }

    /** Shows the local debug log (module-app portion) in a scrollable dialog. */
    private fun showLogDialog() {
        val logText = StringBuilder()
        val appFile = LogFile.appLogFile()
        if (appFile != null && appFile.exists()) {
            logText.append("--- module app log (${appFile.absolutePath}) ---\n\n")
            logText.append(appFile.readText(Charsets.UTF_8))
        } else {
            logText.append("(module app log not written yet)\n")
        }
        logText.append("\n\n--- system hook log (/data/local/tmp/bitlockerdroid.log) ---\n\n")
        try {
            val tmp = java.io.File("/data/local/tmp/bitlockerdroid.log")
            if (tmp.exists()) logText.append(tmp.readText(Charsets.UTF_8))
            else logText.append("(not found — hook may not have written yet)")
        } catch (e: Exception) {
            logText.append("(cannot read: ${e.message})")
        }

        val tv = TextView(this).apply {
            text = logText.toString()
            textSize = 10f
            setPadding(24, 24, 24, 24)
        }
        val sv = ScrollView(this)
        sv.addView(tv)

        AlertDialog.Builder(this)
            .setTitle("BitLocker debug log")
            .setView(sv)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun refresh() {
        val volumes = UnlockManager.unlockedVolumes
        adapter.update(volumes)
        emptyText.visibility = if (volumes.isEmpty()) View.VISIBLE else View.GONE
        volumeList.visibility = if (volumes.isEmpty()) View.GONE else View.VISIBLE

        // Detected-but-locked section.
        val detected = UnlockManager.detectedVolumes
        detectedAdapter.update(detected)
        val anyDetected = detected.isNotEmpty()
        detectedHeader.visibility = if (anyDetected) View.VISIBLE else View.GONE
        detectedEmpty.visibility = if (anyDetected) View.GONE else View.VISIBLE
        detectedList.visibility = if (anyDetected) View.VISIBLE else View.GONE
    }

    /** Launches the unlock dialog for a detected (locked) volume. */
    private fun promptUnlock(devicePath: String) {
        LogFile.write("app", "manual unlock requested for $devicePath")
        UnlockManager.showUnlockDialog(this, devicePath, 0)
    }

    private inner class VolumeAdapter(private val ctx: Context) : BaseAdapter() {
        private val items = ArrayList<UnlockedVolume>()

        fun update(v: List<UnlockedVolume>) {
            items.clear()
            items.addAll(v)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(pos: Int): UnlockedVolume = items[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(ctx)
                .inflate(R.layout.item_volume, parent, false)
            val volume = items[pos]

            v.findViewById<TextView>(R.id.volume_title).text = volume.label
            v.findViewById<TextView>(R.id.volume_path).text = volume.devicePath
            v.findViewById<TextView>(R.id.volume_size).text =
                android.text.format.Formatter.formatFileSize(ctx, volume.size)

            v.findViewById<Button>(R.id.lock_button).setOnClickListener {
                UnlockManager.lock(volume.devicePath)
                Toast.makeText(ctx, R.string.locked, Toast.LENGTH_SHORT).show()
                refresh()
            }
            return v
        }
    }

    private inner class DetectedAdapter(private val ctx: Context) : BaseAdapter() {
        private val items = ArrayList<DetectedVolume>()

        fun update(v: List<DetectedVolume>) {
            items.clear()
            items.addAll(v)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(pos: Int): DetectedVolume = items[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(ctx)
                .inflate(R.layout.item_detected, parent, false)
            val detected = items[pos]

            v.findViewById<TextView>(R.id.detected_path).text = detected.devicePath
            v.findViewById<TextView>(R.id.detected_hint).text =
                getString(R.string.detected_hint)

            v.findViewById<Button>(R.id.detected_unlock_button).setOnClickListener {
                promptUnlock(detected.devicePath)
            }
            return v
        }
    }
}
