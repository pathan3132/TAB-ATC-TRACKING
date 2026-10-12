package com.tabsolutions.tracker

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Driver ke liye simple screen: link (WhatsApp se) khulte hi trip details dikhti hain
 * aur tracking shuru ho jati hai. Driver ke paas Stop / Complete button nahi hai.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etLink: EditText
    private lateinit var tvTrip: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private val prefs by lazy { getSharedPreferences("tab", MODE_PRIVATE) }
    private var token = ""
    private var pendingAutoStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        token = prefs.getString("token", "") ?: ""
        if (!handleIntent(intent) && token.isNotEmpty()) loadTrip(autoStart = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /* ---------------- UI ---------------- */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        col.addView(TextView(this).apply { text = "TAB Solutions"; textSize = 22f })
        col.addView(TextView(this).apply { text = "Trip location tracking"; setPadding(0, 0, 0, dp(12)) })

        etLink = EditText(this).apply { hint = "WhatsApp wala tracking link yahan paste karein" ; setSingleLine(true) }
        col.addView(etLink)

        val btnLoad = Button(this).apply { text = "Trip load karein"; setOnClickListener { onLoadClicked() } }
        col.addView(btnLoad)

        tvTrip = TextView(this).apply { setPadding(0, dp(12), 0, dp(12)); textSize = 15f }
        col.addView(tvTrip)

        btnStart = Button(this).apply {
            text = "Tracking shuru karein"
            isEnabled = false
            setOnClickListener { startFlow() }
        }
        col.addView(btnStart)

        tvStatus = TextView(this).apply { setPadding(0, dp(12), 0, dp(12)); textSize = 16f }
        col.addView(tvStatus)

        col.addView(TextView(this).apply {
            textSize = 13f
            gravity = Gravity.START
            text = "• Tracking shuru hone par aapki live location TAB Solutions ko bheji jayegi.\n" +
                "• Jab tak tracking chalu hai, notification dikhta rahega.\n" +
                "• Trip complete hone par TAB Solutions tracking band kar degi.\n" +
                "• Aap kabhi bhi phone Settings > Apps > TAB Tracker > Permissions mein Location band kar sakte hain."
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun onLoadClicked() {
        val t = parseToken(etLink.text.toString())
        if (t == null) { tvStatus.text = "Link sahi nahi hai."; return }
        token = t
        prefs.edit().putString("token", t).apply()
        loadTrip(autoStart = true)
    }

    /* ---------------- link / token ---------------- */

    private fun parseToken(text: String): String? {
        val s = text.trim()
        if (Regex("^[A-Za-z0-9]{32,64}$").matches(s)) return s
        return Regex("[?&]t=([A-Za-z0-9]{32,64})").find(s)?.groupValues?.get(1)
    }

    private fun handleIntent(i: Intent?): Boolean {
        val data = i?.data?.toString() ?: return false
        val t = parseToken(data) ?: return false
        token = t
        prefs.edit().putString("token", t).apply()
        etLink.setText(data)
        loadTrip(autoStart = true)
        return true
    }

    /* ---------------- trip ---------------- */

    private fun loadTrip(autoStart: Boolean) {
        tvStatus.text = "Trip load ho rahi hai…"
        thread {
            val r = Api.getTrip(token)
            runOnUiThread {
                when {
                    r == null -> tvStatus.text = "Internet / server se connect nahi ho paya."
                    !r.optBoolean("ok") -> {
                        tvTrip.text = ""
                        btnStart.isEnabled = false
                        tvStatus.text = "Link sahi nahi hai ya band ho chuka hai."
                    }
                    else -> showTrip(r.getJSONObject("trip"), autoStart)
                }
            }
        }
    }

    private fun showTrip(t: JSONObject, autoStart: Boolean) {
        tvTrip.text = "Vehicle: ${t.optString("vehicleNo")}\n" +
            "Driver: ${t.optString("driverName")}\n" +
            "Party: ${t.optString("partyName")}\n" +
            "Loading: ${t.optString("loadingLocation")}\n" +
            "Delivery: ${t.optString("deliveryLocation")}\n" +
            "Material: ${t.optString("material")}"
        if (t.optString("status") == "Delivered") {
            btnStart.isEnabled = false
            tvStatus.text = "Trip complete ✓. Tracking band hai."
            return
        }
        btnStart.isEnabled = true
        tvStatus.text = if (t.optString("trackingState") == "Stopped")
            "TAB Solutions ne abhi tracking roki hai. Chalu hote hi app apne aap shuru kar dega."
        else "Tracking shuru karne ke liye neeche button dabayein."
        if (autoStart) startFlow()
    }

    /* ---------------- permissions + start ---------------- */

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startFlow() {
        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            pendingAutoStart = true
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
            return
        }
        beginTracking()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        when (code) {
            1 -> if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) beginTracking()
                 else tvStatus.text = "Location permission zaruri hai. Settings mein Allow karein."
            2 -> askBattery()
        }
    }

    private fun beginTracking() {
        LocationService.start(this, token)
        tvStatus.text = "Tracking chalu ✓ — ab aap app band ya phone lock kar sakte hain."
        askBackground()
    }

    private fun askBackground() {
        if (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            askBattery(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Location: hamesha allow karein")
            .setMessage("Phone lock hone par bhi tracking chalti rahe, iske liye Location permission mein \"Hamesha allow karein\" (Allow all the time) chunein.")
            .setPositiveButton("Theek hai") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
                }
            }
            .setNegativeButton("Baad mein") { _, _ -> askBattery() }
            .show()
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Battery optimization band karein")
            .setMessage("Kai phones background app ko band kar dete hain. Agle screen par \"Allow\" dabayein taki tracking na ruke.")
            .setPositiveButton("Theek hai") { _, _ ->
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Baad mein", null)
            .show()
    }
}
