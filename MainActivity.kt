package com.tabsolutions.tracker

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Driver ke liye screen.
 * - Jab tak Location "Hamesha allow" (Allow all the time) nahi milti aur server driver ki location
 *   accept nahi karta, tab tak trip details NAHI dikhti.
 * - Driver ke paas Stop / Complete button nahi hai (admin dashboard se hota hai).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etLink: EditText
    private lateinit var tvGate: TextView
    private lateinit var tvTrip: TextView
    private lateinit var btnStart: Button
    private val prefs by lazy { getSharedPreferences("tab", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private var token = ""

    private val poller = object : Runnable {
        override fun run() { renderState(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        token = prefs.getString("token", "") ?: ""
        if (!handleIntent(intent)) renderState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        token = prefs.getString("token", "") ?: ""
        // Settings se wapas aane par: permission mil gayi ho to tracking chalu rakho
        if (token.isNotEmpty() && prefs.getString("notice", "").isNullOrEmpty() && hasAll()) {
            LocationService.start(this, token)
        }
        ui.post(poller)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poller)
    }

    /* ---------------- UI ---------------- */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.atc_logo)      // android/atc_logo.png ko res/drawable mein daalein
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        col.addView(TextView(this).apply {
            text = "Munna & Asif"
            gravity = Gravity.CENTER
            textSize = 13f
            setPadding(0, 0, 0, dp(14))
        })

        etLink = EditText(this).apply {
            hint = "WhatsApp wala link yahan paste karein"
            setSingleLine(true)
        }
        col.addView(etLink)
        col.addView(Button(this).apply { text = "Link kholein"; setOnClickListener { onLoadClicked() } })

        tvGate = TextView(this).apply { textSize = 17f; setPadding(0, dp(14), 0, dp(10)) }
        col.addView(tvGate)

        btnStart = Button(this).apply {
            text = "📍 Location Allow karein"
            visibility = View.GONE
            setOnClickListener { startFlow() }
        }
        col.addView(btnStart)

        tvTrip = TextView(this).apply {
            textSize = 18f
            visibility = View.GONE
            setPadding(dp(4), dp(14), dp(4), dp(14))
        }
        col.addView(tvTrip)

        col.addView(TextView(this).apply {
            textSize = 12f
            text = "🔒 Aapki location sirf is trip ke liye ATC Transport ko dikhegi. " +
                "Tracking chalu rehne par notification dikhta rahega. " +
                "Aap kabhi bhi Settings > Apps > ATC Tracker > Permissions mein Location band kar sakte hain."
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    /* ---------------- state ---------------- */

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // "Hamesha allow" = fine location + (Android 10+ par) background location
    private fun hasAll() = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    private fun noticeText(n: String) = when (n) {
        "LINK_IN_USE" -> "🔒 Yeh link kisi aur phone par chal raha hai.\nATC Transport se sampark karein."
        "CLOSED" -> "✅ Trip complete. Dhanyavaad!"
        else -> "🚫 Link sahi nahi hai ya band ho chuka hai.\nATC Transport se naya link maangein."
    }

    private fun renderState() {
        val notice = prefs.getString("notice", "") ?: ""
        if (notice.isNotEmpty()) {
            tvTrip.visibility = View.GONE; btnStart.visibility = View.GONE
            tvGate.text = noticeText(notice); return
        }
        if (token.isEmpty()) {
            tvTrip.visibility = View.GONE; btnStart.visibility = View.GONE
            tvGate.text = "WhatsApp ka link kholein ya upar paste karein."; return
        }
        if (!hasAll()) {
            tvTrip.visibility = View.GONE; btnStart.visibility = View.VISIBLE
            tvGate.text = "Trip details dekhne ke liye Location ko \"Hamesha allow karein\" (Allow all the time) zaruri hai.\n\n" +
                "ट्रिप की जानकारी के लिए लोकेशन “हमेशा अनुमति दें” करें।"
            return
        }
        btnStart.visibility = View.GONE
        val t = prefs.getString("trip", null)
        if (t == null) {
            tvTrip.visibility = View.GONE
            tvGate.text = "Location mil rahi hai… thoda ruko"
        } else {
            tvGate.text = "📍 Location ON ✓"
            tvTrip.visibility = View.VISIBLE
            tvTrip.text = format(JSONObject(t))
        }
    }

    private fun format(t: JSONObject): String {
        val sb = StringBuilder()
        sb.append("गाड़ी नंबर\n").append(t.optString("vehicleNo")).append("\n\n")
        sb.append("कहाँ से (Loading)\n").append(t.optString("loadingLocation")).append("\n\n")
        sb.append("कहाँ तक (Delivery)\n").append(t.optString("deliveryLocation")).append("\n\n")
        sb.append("माल: ").append(t.optString("material")).append("\n")
        sb.append("पार्टी: ").append(t.optString("partyName")).append("\n")
        sb.append("ड्राइवर: ").append(t.optString("driverName")).append("\n")
        sb.append("Trip ID: ").append(t.optString("tripId")).append("\n")
        val r = t.optString("remarks")
        if (r.isNotEmpty()) sb.append("\nखास निर्देश: ").append(r).append("\n")
        return sb.toString()
    }

    /* ---------------- link / token ---------------- */

    private fun parseToken(text: String): String? {
        val s = text.trim()
        if (Regex("^[A-Za-z0-9]{32,64}$").matches(s)) return s
        return Regex("[?&]t=([A-Za-z0-9]{32,64})").find(s)?.groupValues?.get(1)
    }

    private fun onLoadClicked() {
        val t = parseToken(etLink.text.toString())
        if (t == null) { tvGate.text = "Link sahi nahi hai."; return }
        setToken(t)
    }

    private fun handleIntent(i: Intent?): Boolean {
        val data = i?.data?.toString() ?: return false
        val t = parseToken(data) ?: return false
        etLink.setText(data)
        setToken(t)
        return true
    }

    private fun setToken(t: String) {
        token = t
        prefs.edit().putString("token", t).remove("trip").remove("notice").apply()
        renderState()
        tvGate.text = "Link check ho raha hai…"
        thread {
            val r = Api.getTrip(t, Device.id(this))
            runOnUiThread {
                when {
                    r == null -> tvGate.text = "Internet / server se connect nahi ho paya."
                    !r.optBoolean("ok") -> prefs.edit().putString("notice", "INVALID").apply()
                    r.optBoolean("linkInUse") -> prefs.edit().putString("notice", "LINK_IN_USE").apply()
                    r.optJSONObject("trip")?.optString("status") == "Delivered" ->
                        prefs.edit().putString("notice", "CLOSED").apply()
                    else -> startFlow()
                }
                renderState()
            }
        }
    }

    /* ---------------- permissions + start ---------------- */

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
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
            return
        }
        if (!hasAll()) { askBackground(); return }
        beginTracking()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        when (code) {
            1 -> if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) startFlow() else renderState()
            2 -> if (hasAll()) beginTracking() else renderState()
        }
    }

    private fun askBackground() {
        AlertDialog.Builder(this)
            .setTitle("Location: Hamesha allow karein")
            .setMessage("Trip details dekhne aur phone lock hone par bhi tracking chalne ke liye Location permission mein \"Hamesha allow karein\" (Allow all the time) chunein.")
            .setPositiveButton("Theek hai") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    // Android 11+: system dialog nahi aata, Settings mein jaana padta hai
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
                }
            }
            .setNegativeButton("Abhi nahi", null)
            .show()
    }

    private fun beginTracking() {
        LocationService.start(this, token)
        renderState()
        askBattery()
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
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
