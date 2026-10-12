package com.tabsolutions.tracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Foreground service: phone lock ho ya app background mein ho, location bhejta rehta hai.
 * Stop / complete sirf admin dashboard se hota hai (server se response milne par service khud band hoti hai).
 */
class LocationService : Service() {

    companion object {
        const val EXTRA_TOKEN = "token"
        private const val CHANNEL_ID = "tab_tracking"
        private const val NOTIF_ID = 1001
        private const val RESUME_POLL_MS = 60_000L

        fun start(ctx: Context, token: String) {
            val i = Intent(ctx, LocationService::class.java).putExtra(EXTRA_TOKEN, token)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private lateinit var client: FusedLocationProviderClient
    private val io = Executors.newSingleThreadExecutor()   // queue sirf is thread par chhui jati hai
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<JSONObject>()
    private var token = ""
    private var updatesOn = false
    private var sentCount = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val points = result.locations.map { toPoint(it) }
            io.execute {
                queue.addAll(points)
                while (queue.size > 500) queue.removeFirst()
                flush()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("tab", MODE_PRIVATE)
        token = intent?.getStringExtra(EXTRA_TOKEN) ?: prefs.getString("token", "") ?: ""
        if (token.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        prefs.edit().putString("token", token).apply()

        val n = buildNotification("Tracking chalu hai", "Aapki location TAB Solutions ko bheji ja rahi hai")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
        // Server se current state le kar tay karein: chalu karein ya paused rahein
        io.execute { checkState() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopUpdates()
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    /* ---------------- state ---------------- */

    private fun checkState() {
        val r = Api.getTrip(token)
        if (r == null) {            // net nahi: tracking chalu rakho, baad mein sync hoga
            handler.post { startUpdates() }
            return
        }
        if (!r.optBoolean("ok")) { finishTrip(); return }
        val trip = r.optJSONObject("trip")
        when {
            trip?.optString("status") == "Delivered" -> finishTrip()
            trip?.optString("trackingState") == "Stopped" -> pause()
            else -> handler.post { startUpdates() }
        }
    }

    private fun startUpdates() {
        if (updatesOn) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AppConfig.PING_INTERVAL_MS)
            .setMinUpdateIntervalMillis(AppConfig.PING_INTERVAL_MS / 2)
            .build()
        try {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            updatesOn = true
            updateNotification("Tracking chalu hai", "Aapki location TAB Solutions ko bheji ja rahi hai")
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopUpdates() {
        if (updatesOn) {
            client.removeLocationUpdates(callback)
            updatesOn = false
        }
    }

    // Admin ne tracking roki: location lena band, par service har minute check karti hai ki dobara chalu hui ya nahi
    private fun pause() {
        queue.clear()
        handler.post {
            stopUpdates()
            updateNotification("Tracking abhi band hai", "TAB Solutions ne tracking roki hai")
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ io.execute { checkState() } }, RESUME_POLL_MS)
        }
    }

    private fun finishTrip() {
        queue.clear()
        getSharedPreferences("tab", MODE_PRIVATE).edit().remove("token").apply()
        handler.post {
            stopUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /* ---------------- upload ---------------- */

    private fun flush() {
        if (queue.isEmpty()) return
        val batch = queue.take(50)
        val arr = JSONArray().also { a -> batch.forEach { a.put(it) } }
        val res = Api.post(
            JSONObject().put("action", "postLocations").put("token", token).put("points", arr)
        )
        if (res == null) {
            updateNotification("Tracking chalu, internet nahi", "Location phone mein save hai, net aate hi bhej di jayegi")
            return
        }
        if (res.optBoolean("ok")) {
            repeat(batch.size) { queue.removeFirst() }
            sentCount += batch.size
            updateNotification("Tracking chalu hai", "Bheje gaye points: $sentCount")
            if (queue.isNotEmpty()) flush()
            return
        }
        when (res.optString("error")) {
            "TRIP_CLOSED", "INVALID_LINK" -> finishTrip()
            "TRACKING_STOPPED" -> pause()
            "NO_VALID_POINTS" -> repeat(batch.size) { queue.removeFirst() }
            else -> { /* server busy: agli baar dobara */ }
        }
    }

    private fun toPoint(l: Location): JSONObject = JSONObject()
        .put("lat", l.latitude)
        .put("lng", l.longitude)
        .put("acc", l.accuracy.toDouble())
        .put("speed", if (l.hasSpeed()) l.speed.toDouble() else JSONObject.NULL)
        .put("ts", l.time)

    /* ---------------- notification ---------------- */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Trip tracking", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, text))
    }
}
