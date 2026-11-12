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
import java.util.Collections

class NotificationForwarderService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: AppPreferences
    private lateinit var repository: SupabaseRepository

    // Кэш недавно отправленных сообщений для исключения дубликатов (key -> timestamp)
    private val sentCache = Collections.synchronizedMap(object : LinkedHashMap<String, Long>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 150
        }
    })

    // Системные пакеты, которые всегда игнорируются
    private val ignoredPackages = setOf(
        "android",
        "com.android.systemui",
        "com.teliphone.forwarder",
        "com.google.android.googlequicksearchbox"
    )

    // Регулярные выражения для распознавания сводных уведомлений чатов
    private val summaryRegex = Regex("""\d+\s+(нов(ых|ое|ые)?\s+)?(сообщен|диалог|чат)""", RegexOption.IGNORE_CASE)
    private val englishSummaryRegex = Regex("""\d+\s+new\s+messages""", RegexOption.IGNORE_CASE)

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

        // Проверяем фильтр выбранных приложений
        if (!prefs.forwardAllApps) {
            val selected = prefs.selectedPackages
            if (!selected.contains(packageName)) {
                // Пакет не входит в список разрешенных
                return
            }
        }

        val notification = sbn.notification ?: return

        // 1. Игнорируем постоянные фоновые уведомления (музыка, загрузки, службы)
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing) return

        // 2. Игнорируем групповые сводные уведомления ("X новых сообщений из Y чатов")
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            Log.d(TAG, "Пропущено групповое уведомление (FLAG_GROUP_SUMMARY) из $packageName")
            return
        }

        val extras = notification.extras ?: return

        // Извлекаем заголовок и текст сообщения
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val finalBody = bigText?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() }

        // Если нет ни заголовка, ни текста — пропускаем
        if (title.isNullOrBlank() && finalBody.isNullOrBlank()) return

        val sender = title?.trim() ?: packageName
        val body = finalBody?.trim() ?: "(Нет текста)"

        // 3. Проверка на текст-сводку типа "15 новых сообщений из 8 чатов"
        if (summaryRegex.containsMatchIn(body) || englishSummaryRegex.containsMatchIn(body)) {
            Log.d(TAG, "Пропущена сводка по тексту: $body")
            return
        }

        // 4. Защита от дубликатов (дедупликация в пределах 45 секунд)
        val deduplicationKey = "$packageName:::$sender:::$body"
        val now = System.currentTimeMillis()
        val lastSent = sentCache[deduplicationKey]

        if (lastSent != null && (now - lastSent) < 45_000) {
            Log.d(TAG, "Пропущен дубликат ($deduplicationKey), отправлен ${(now - lastSent) / 1000} сек назад")
            return
        }

        // Запоминаем время отправки
        sentCache[deduplicationKey] = now

        Log.d(TAG, "Перехвачено чистое уведомление из [$packageName] $sender: $body")

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
