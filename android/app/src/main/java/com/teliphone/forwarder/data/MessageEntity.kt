package com.teliphone.forwarder.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MessagePayload(
    val sender: String,
    val body: String,
    val source: String, // "sms" or "notification"
    val packageName: String? = null,
    val deviceId: String? = null,
    val receivedAt: String = getCurrentIsoTimestamp()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("sender", sender)
        json.put("body", body)
        json.put("source", source)
        if (packageName != null) json.put("package_name", packageName)
        if (deviceId != null) json.put("device_id", deviceId)
        json.put("received_at", receivedAt)
        return json.toString()
    }

    companion object {
        fun getCurrentIsoTimestamp(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }
}

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("teliphone_prefs", Context.MODE_PRIVATE)

    var supabaseUrl: String
        get() = prefs.getString("supabase_url", "https://jhjesirowlvuahlqlxqt.supabase.co") ?: "https://jhjesirowlvuahlqlxqt.supabase.co"
        set(value) = prefs.edit().putString("supabase_url", value.trim().trimEnd('/')).apply()

    var supabaseAnonKey: String
        get() = prefs.getString("supabase_anon_key", "sb_publishable_qGNun5qq4twlmJvGfX1WsA_vl_udqqq") ?: "sb_publishable_qGNun5qq4twlmJvGfX1WsA_vl_udqqq"
        set(value) = prefs.edit().putString("supabase_anon_key", value.trim()).apply()

    var deviceName: String
        get() = prefs.getString("device_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(value) = prefs.edit().putString("device_name", value.trim()).apply()

    var isSmsForwardingEnabled: Boolean
        get() = prefs.getBoolean("sms_enabled", true)
        set(value) = prefs.edit().putBoolean("sms_enabled", value).apply()

    var isNotificationForwardingEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", false)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    var lastLog: String
        get() = prefs.getString("last_log", "Служба готова к работе") ?: ""
        set(value) = prefs.edit().putString("last_log", value).apply()

    val isConfigured: Boolean
        get() = supabaseUrl.isNotEmpty() && supabaseAnonKey.isNotEmpty()
}
