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

        // Группируем части длинного составного SMS по отправителю
        val sender = messages[0].displayOriginatingAddress ?: "Неизвестный"
        val bodyBuilder = StringBuilder()
        for (sms in messages) {
            bodyBuilder.append(sms.displayMessageBody)
        }
        val fullBody = bodyBuilder.toString()

        Log.d(TAG, "Получено SMS от $sender: $fullBody")

        // Выполняем асинхронную отправку в Supabase через goAsync()
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
    }
}
