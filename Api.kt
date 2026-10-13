package com.tabsolutions.tracker

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Is phone ki pehchaan: link sirf pehle phone par chalta hai. */
object Device {
    fun id(ctx: Context): String {
        val p = ctx.getSharedPreferences("tab", Context.MODE_PRIVATE)
        var d = p.getString("device", null)
        if (d == null) {
            d = UUID.randomUUID().toString().replace("-", "")
            p.edit().putString("device", d).apply()
        }
        return d
    }
}

object Api {
    /** Apps Script ko POST. Network error par null return karta hai. */
    fun post(body: JSONObject): JSONObject? {
        return try {
            val conn = URL(AppConfig.API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.instanceFollowRedirects = true // Apps Script 302 redirect karta hai
            conn.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    /** Sirf status deta hai (trip details nahi). Details location accept hone ke baad hi milti hain. */
    fun getTrip(token: String, deviceId: String): JSONObject? =
        post(JSONObject().put("action", "getTrip").put("token", token).put("deviceId", deviceId))
}
