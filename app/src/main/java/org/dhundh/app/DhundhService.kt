package org.dhundh.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.dhundh.core.BrakeDetector
import org.dhundh.core.HazardMessage
import org.dhundh.core.Inbox
import org.dhundh.core.Relevance
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs while driving: watches GNSS and motion sensors for hard braking,
 * broadcasts signed warnings, listens for other phones' warnings, relays
 * fresh ones back along the road and alerts the driver when one matters.
 */
@SuppressLint("MissingPermission") // MainActivity requests every permission before starting us
class DhundhService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val ACTION_TEST = "org.dhundh.TEST"
        private const val CHANNEL_RUN = "running"
        private const val CHANNEL_ALERT = "alerts"
    }

    private val signer = BcSigner()
    private val inbox = Inbox(signer)
    private val detector = BrakeDetector()
    private val sequence = AtomicInteger((0..0xFFFF).random())
    private lateinit var radio: BleRadio
    private lateinit var alerts: Alerts
    private lateinit var log: FieldLog
    private var fix: Location? = null
    private var gravity = floatArrayOf(0f, 0f, 9.81f)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notification = runningNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
        alerts = Alerts(this)
        log = FieldLog(File(getExternalFilesDir(null) ?: filesDir, "dhundh_log.csv"))
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null) {
            stopSelf()
            return
        }
        radio = BleRadio(bt) { bytes, rssi, phy -> onPacket(bytes, rssi, phy) }
        radio.startScanning()
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this, Looper.getMainLooper())
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        Status.update { it.copy(running = true, codedPhy = radio.codedPhy, extended = radio.extended) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) send(HazardMessage.Type.TEST, 0.0)
        return START_STICKY
    }

    override fun onDestroy() {
        if (::radio.isInitialized) radio.stopScanning()
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        if (::alerts.isInitialized) alerts.close()
        if (::log.isInitialized) log.close()
        Status.update { it.copy(running = false) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sensing

    override fun onLocationChanged(location: Location) {
        fix = location
        val t = SystemClock.elapsedRealtime() / 1000.0
        val event = detector.onSpeed(t, location.speed.toDouble())
        Status.update { it.copy(speedKmh = location.speed * 3.6, gpsAccuracyM = location.accuracy) }
        if (event != null) send(event, detector.recentDecel(t))
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> gravity = e.values.clone()
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val t = e.timestamp / 1e9
                val event = detector.onAccel(t, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(),
                    gravity[0].toDouble(), gravity[1].toDouble(), gravity[2].toDouble())
                if (event != null) send(event, 0.0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // Android 8 and 9 have no default bodies for these, so they must exist.
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    // ---------------------------------------------------------------- radio

    private fun send(type: HazardMessage.Type, decel: Double) {
        val f = fix ?: return // no position, nothing useful to say
        val msg = HazardMessage(type, 0, f.time, f.latitude, f.longitude, f.bearing.toDouble(),
            f.speed.toDouble(), decel, sequence.incrementAndGet(), signer.publicKey(), null).signed(signer)
        radio.broadcast(msg.encode())
        log.write("sent", msg, rssi = null, phy = null, verdict = null)
        Status.update { it.copy(sent = it.sent + 1, lastEvent = "Sent: ${label(type)}") }
    }

    private fun onPacket(bytes: ByteArray, rssi: Int, phy: Int) {
        val f = fix
        val lat = f?.latitude ?: 0.0
        val lon = f?.longitude ?: 0.0
        val course = f?.bearing?.toDouble() ?: 0.0
        val speed = f?.speed?.toDouble() ?: 0.0
        val out = inbox.receive(bytes, lat, lon, course, speed, System.currentTimeMillis())
        val m = out.message ?: return
        log.write("received", m, rssi, phy, out.verdict)
        out.relay?.let { radio.broadcast(it.encode()) }
        val v = out.verdict
        if (m.type == HazardMessage.Type.TEST) {
            alerts.test(v?.distanceM ?: Double.NaN)
        } else if (v != null && v.level != Relevance.Level.IGNORE) {
            alerts.warn(m.type, v)
            showAlertNotification(m.type, v)
        }
        Status.update { it.copy(received = it.received + 1, lastEvent = "Heard: ${label(m.type)}, " +
            (v?.distanceM?.let { d -> "${d.toInt()} m" } ?: "?") + ", hop ${m.hops}, RSSI $rssi") }
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUN, "Running", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Hazard alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun runningNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_RUN)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("Dhundh is listening")
        .setContentText("Warnings from vehicles ahead will sound here.")
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    private fun showAlertNotification(type: HazardMessage.Type, v: Relevance.Verdict) {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(label(type))
            .setContentText("${v.distanceM.toInt()} m ahead. Slow down now.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(openApp(), true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(2, n)
    }
}

fun label(type: HazardMessage.Type): String = when (type) {
    HazardMessage.Type.HARD_BRAKE -> "Hard braking ahead"
    HazardMessage.Type.STOPPED -> "Stopped vehicle ahead"
    HazardMessage.Type.CRASH -> "Crash ahead"
    HazardMessage.Type.TEST -> "Test warning"
}
