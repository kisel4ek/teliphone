package com.teliphone.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.teliphone.forwarder.data.AppPreferences
import com.teliphone.forwarder.data.MessagePayload
import com.teliphone.forwarder.data.SupabaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = AppPreferences(context)
        if (!prefs.isSmsForwardingEnabled) {
            Log.d(TAG, "Пересылка SMS отключена в настройках")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Группируем части составного SMS
        val sender = messages[0].displayOriginatingAddress ?: "Неизвестный"
        val bodyBuilder = StringBuilder()
        for (sms in messages) {
            bodyBuilder.append(sms.displayMessageBody)
        }
        val fullBody = bodyBuilder.toString().trim()

        // Защита от дубликатов SMS (например, повторные intents от системы)
        val deduplicationKey = "$sender:::$fullBody"
        val now = System.currentTimeMillis()
        val lastSent = sentSmsCache[deduplicationKey]
        if (lastSent != null && (now - lastSent) < 45_000) {
            Log.d(TAG, "Пропущено дублирующее SMS от $sender")
            return
        }
        sentSmsCache[deduplicationKey] = now

        Log.d(TAG, "Получено уникальное SMS от $sender: $fullBody")

        val pendingResult = goAsync()
        val repository = SupabaseRepository(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = MessagePayload(
                    sender = sender,
                    body = fullBody,
                    source = "sms",
                    packageName = "android.telephony.sms",
                    deviceId = prefs.deviceName
                )
                repository.sendMessage(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка пересылки SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private val sentSmsCache = Collections.synchronizedMap(object : LinkedHashMap<String, Long>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 100
            }
        })
    }
}
