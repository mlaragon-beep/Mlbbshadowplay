package com.mlbb.replayrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqProjection = 100
    private val reqPerms = 101
    private var askedPerms = false
    private var askedOverlay = false
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "MLBB Replay Recorder"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Keeps the last seconds of your game in memory. Tap the red bubble to save them."
            setPadding(0, dp(4), 0, dp(8))
        })

        spinner(root, "Resolution", listOf("480p", "720p (recommended)", "1080p"), listOf(480, 720, 1080), Prefs.HEIGHT, 720)
        spinner(root, "Frame rate", listOf("30 fps", "60 fps"), listOf(30, 60), Prefs.FPS, 30)
        spinner(root, "Bitrate", listOf("4 Mbps", "8 Mbps", "12 Mbps", "16 Mbps"), listOf(4, 8, 12, 16), Prefs.BITRATE, 8)
        spinner(root, "Replay length (uses more RAM when longer)", listOf("15 seconds", "30 seconds", "60 seconds", "90 seconds"), listOf(15, 30, 60, 90), Prefs.BUFFER, 30)
        checkbox(root, "Record game / internal audio", Prefs.AUDIO, true)
        checkbox(root, "Show floating button (tap = save replay, hold = start/stop recording)", Prefs.BUBBLE, true)

        root.addView(Button(this).apply {
            text = "Start"
            setOnClickListener { onStartClicked() }
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(Intent(this@MainActivity, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                status.text = "Stopped."
            }
        })
        status = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (RecorderService.running) "Replay buffer is ON. Open MLBB and play." else "Off."
    }

    private fun spinner(parent: LinearLayout, label: String, names: List<String>, values: List<Int>, key: String, def: Int) {
        parent.addView(TextView(this).apply {
            text = label
            setPadding(0, dp(12), 0, 0)
        })
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        sp.setSelection(values.indexOf(Prefs.getInt(this, key, def)).coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setInt(this@MainActivity, key, values[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        parent.addView(sp)
    }

    private fun checkbox(parent: LinearLayout, label: String, key: String, def: Boolean) {
        val cb = CheckBox(this)
        cb.text = label
        cb.isChecked = Prefs.getBool(this, key, def)
        cb.setOnCheckedChangeListener { _, on -> Prefs.setBool(this, key, on) }
        parent.addView(cb)
    }

    private fun onStartClicked() {
        if (RecorderService.running) {
            Toast.makeText(this, "Already running", Toast.LENGTH_SHORT).show()
            return
        }
        if (!askedPerms) {
            val need = ArrayList<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), reqPerms)
                return
            }
        }
        if (!askedOverlay && Prefs.getBool(this, Prefs.BUBBLE, true) && !Settings.canDrawOverlays(this)) {
            askedOverlay = true
            Toast.makeText(this, "Allow 'Display over other apps', then press back and tap Start", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askedPerms = true
        onStartClicked()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecorderService::class.java)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_CODE, resultCode)
                .putExtra(RecorderService.EXTRA_DATA, data)
            startForegroundService(i)
            status.text = "Replay buffer is ON. Open MLBB and play. Tap the red bubble to save."
        } else if (requestCode == reqProjection) {
            status.text = "Screen capture permission was denied."
        }
    }
}
