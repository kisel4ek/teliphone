package com.teliphone.forwarder.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.teliphone.forwarder.data.AppPreferences
import com.teliphone.forwarder.data.MessagePayload
import com.teliphone.forwarder.data.SupabaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationForwarderService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: AppPreferences
    private lateinit var repository: SupabaseRepository

    // Пакеты, уведомления от которых обычно не являются сообщениями (системные)
    private val ignoredPackages = setOf(
        "android",
        "com.android.systemui",
        "com.teliphone.forwarder", // свои собственные
        "com.google.android.googlequicksearchbox"
    )

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(applicationContext)
        repository = SupabaseRepository(applicationContext)
        Log.d(TAG, "NotificationListenerService создан")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        if (!prefs.isNotificationForwardingEnabled) {
            return
        }

        val packageName = sbn.packageName ?: return
        if (ignoredPackages.contains(packageName)) return

        val notification = sbn.notification ?: return

        // Игнорируем постоянные фоновые уведомления (музыка, загрузки и т.д.)
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing) return

        val extras = notification.extras ?: return

        // Извлекаем заголовок и текст сообщения
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val finalBody = bigText?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() }

        // Если нет ни заголовка, ни текста — пропускаем
        if (title.isNullOrBlank() && finalBody.isNullOrBlank()) return

        val sender = title ?: packageName
        val body = finalBody ?: "(Нет текста)"

        Log.d(TAG, "Перехвачено уведомление из [$packageName] $sender: $body")

        serviceScope.launch {
            val payload = MessagePayload(
                sender = "$sender ($packageName)",
                body = body,
                source = "notification",
                packageName = packageName,
                deviceId = prefs.deviceName
            )
            repository.sendMessage(payload)
        }
    }

    companion object {
        private const val TAG = "NotifListener"
    }
}
