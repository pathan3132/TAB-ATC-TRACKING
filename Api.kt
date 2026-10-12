package com.tabsolutions.tracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    fun getTrip(token: String): JSONObject? =
        post(JSONObject().put("action", "getTrip").put("token", token))
}
