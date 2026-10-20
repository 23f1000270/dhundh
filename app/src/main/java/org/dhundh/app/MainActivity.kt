package org.dhundh.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var radio: TextView
    private lateinit var last: TextView
    private lateinit var toggle: Button
    private val listener: (State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        radio = findViewById(R.id.radio)
        last = findViewById(R.id.last)
        toggle = findViewById(R.id.toggle)
        toggle.setOnClickListener {
            if (Status.state.running) stopService(Intent(this, DhundhService::class.java))
            else if (hasPermissions()) start() else requestPermissions()
        }
        findViewById<Button>(R.id.test).setOnClickListener {
            if (Status.state.running) {
                startService(Intent(this, DhundhService::class.java).setAction(DhundhService.ACTION_TEST))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Status.observe(listener)
    }

    override fun onStop() {
        Status.remove(listener)
        super.onStop()
    }

    private fun render(s: State) {
        toggle.text = getString(if (s.running) R.string.stop else R.string.start)
        status.text = if (s.running) {
            getString(R.string.status_running, s.speedKmh.toInt(), s.sent, s.received)
        } else {
            getString(R.string.status_stopped)
        }
        radio.text = when {
            !s.running -> ""
            !s.extended -> getString(R.string.radio_unsupported)
            s.codedPhy -> getString(R.string.radio_coded)
            else -> getString(R.string.radio_1m)
        }
        last.text = s.lastEvent
    }

    private fun needed(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermissions() = needed().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() = ActivityCompat.requestPermissions(this, needed().toTypedArray(), 7)

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (hasPermissions()) start() else last.text = getString(R.string.need_permissions)
    }

    private fun start() = ContextCompat.startForegroundService(this, Intent(this, DhundhService::class.java))
}
