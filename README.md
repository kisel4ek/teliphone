# Teliphone — Пересылка сообщений с Android на iOS через Supabase

Комплексное решение для синхронизации SMS и push-уведомлений с Android смартфона на iPhone с базой данных **Supabase** и отправкой push-уведомлений (APNs).

---

## 🏗 Архитектура системы

```
 [ Android Смартфон ]
    ├── Входящие SMS (BroadcastReceiver)
    └── Уведомления мессенджеров/банков (NotificationListenerService)
            │
            ▼ (HTTPS POST /rest/v1/messages)
 [ Supabase (PostgreSQL + Realtime) ]
    ├── Таблица `messages` (хранилище сообщений)
    ├── Таблица `devices` (APNs токены устройств iOS)
    ├── Supabase Realtime WebSocket (мгновенное обновление в приложении)
    └── Database Webhook / Edge Function (`push-notification`)
            │
            ▼ (Apple Push Notification Service — APNs / OneSignal)
 [ iPhone (iOS App) ]
    ├── Системный Push на экран блокировки (со звуком и предпросмотром)
    ├── Копирование 2FA / OTP кодов в 1 тап
    └── Список сообщений с поиском и фильтрами (SMS / Уведомления)
```

---

## 📁 Структура проекта

* `supabase/`
  * `schema.sql` — SQL-миграция: создание таблиц `messages` и `devices`, включение RLS и Realtime.
  * `functions/push-notification/index.ts` — Deno Edge Function для отправки Push на iOS через Apple APNs HTTP/2 или OneSignal.
* `android/` — Нативный Android проект (Kotlin, Jetpack, OkHttp):
  * `SmsReceiver.kt` — перехватчик входящих SMS.
  * `NotificationForwarderService.kt` — перехватчик уведомлений (WhatsApp, Telegram, Сбер, Т-Банк и т.д.).
  * `ForwarderForegroundService.kt` — фоновая служба для предотвращения усыпления Android.
  * `MainActivity.kt` — экран настройки и теста.
* `ios/` — Нативное iOS приложение (SwiftUI, iOS 16+):
  * `TeliphoneApp.swift` / `AppDelegate.swift` — обработка APNs пушей.
  * `SupabaseService.swift` — работа с REST API и WebSockets Supabase Realtime.
  * `ContentView.swift` — интерфейс сообщений с быстрым копированием кодов авторизации.

---

## 🚀 Пошаговая инструкция по настройке

### Шаг 1. Настройка Supabase

1. Зарегистрируйтесь бесплатно на [supabase.com](https://supabase.com) и создайте новый проект.
2. Перейдите в раздел **SQL Editor** в левом меню.
3. Откройте файл `supabase/schema.sql` из этого репозитория, скопируйте его содержимое, вставьте в редактор и нажмите **Run**.
4. Перейдите в **Project Settings -> API** и сохраните два значения:
   * **Project URL** (например: `https://abcdefghijkl.supabase.co`)
   * **anon public key** (длинная строка токена)

---

### Шаг 2. Запуск Android приложения

1. Откройте папку `android/` в **Android Studio**.
2. Подключите ваш Android-телефон по USB (или соберите APK через `Build -> Build Bundle(s) / APK(s) -> Build APK(s)`).
3. Запустите приложение на телефоне.
4. В приложении:
   * Введите ваш **Supabase URL** и **Anon Key**.
   * Нажмите **«Сохранить настройки»**.
   * Нажмите **«1. Разрешить чтение SMS»** и подтвердите системный запрос.
   * *(По желанию)* Нажмите **«2. Доступ к уведомлениям»** и включите переключатель напротив *Teliphone*, если хотите также пересылать сообщения из Telegram, WhatsApp и банков.
   * Нажмите **«3. Отключить усыпление»** и разрешите работу без ограничений аккумулятора (критично для телефонов Xiaomi/Samsung/Huawei).
   * Нажмите зеленую кнопку **«Отправить тестовое сообщение»** — статус должен смениться на `[Успех]`.

---

### Шаг 3. Запуск iOS приложения

1. Откройте папку `ios/` в **Xcode**.
2. В настройках проекта (*Signing & Capabilities*):
   * Выберите вашу команду разработчика (*Team*).
   * Укажите уникальный *Bundle Identifier* (например `com.yourname.teliphone`).
   * Нажмите `+ Capability` и добавьте:
     * **Push Notifications**
     * **Background Modes** (отметьте пункт *Remote notifications*)
3. Запустите приложение на реальном iPhone (push-уведомления не поддерживаются на симуляторе).
4. Разрешите отправку уведомлений при первом запуске.
5. Нажмите на иконку шестеренки (Настройки) в правом верхнем углу и введите те же **Supabase URL** и **Anon Key**.
6. Индикатор в левом верхнем углу загорится зеленым цветом **«Live»** — теперь входящие сообщения появляются мгновенно!

---

### Шаг 4. Настройка системных Push-уведомлений на закрытый iPhone (APNs)

Когда приложение на iOS **открыто**, сообщения приходят мгновенно через встроенный **Supabase Realtime**.

Чтобы уведомления приходили на **заблокированный экран**, когда приложение выгружено из памяти:
1. В аккаунте [Apple Developer](https://developer.apple.com/account/resources/authkeys/list):
   * Создайте ключ **Apple Push Notifications service (APNs)** (файл `.p8`).
   * Запишите **Key ID** и ваш **Team ID**.
2. В Supabase перейдите в **Project Settings -> Edge Functions -> Secrets** и добавьте:
   * `APNS_KEY_ID`: ваш Key ID (например: `M8K23J4KLL`)
   * `APNS_TEAM_ID`: ваш Team ID (например: `9Y27L8M47Q`)
   * `APNS_BUNDLE_ID`: ваш Bundle Identifier (например: `com.yourname.teliphone`)
   * `APNS_P8_PRIVATE_KEY`: текст ключа `.p8` (включая строки `-----BEGIN PRIVATE KEY-----`)
   * `APNS_PRODUCTION`: `false` (для тестирования через Xcode) или `true` (для TestFlight/App Store).
3. Разверните Edge Function через терминал:
   ```bash
   npx supabase functions deploy push-notification
   ```
4. В консоли Supabase перейдите в **Database -> Webhooks** -> **Create Webhook**:
   * Name: `send-push-on-message`
   * Table: `messages`
   * Events: `INSERT`
   * Webhook type: `Supabase Edge Function` -> выберите `push-notification`.

*(Альтернатива без покупки Apple Developer: можно использовать бесплатный OneSignal, добавив переменные `ONESIGNAL_APP_ID` и `ONESIGNAL_API_KEY` в секреты Supabase)*.

---

## 🔒 Безопасность и конфиденциальность

* Все данные передаются напрямую между вашим Android-телефоном, вашей собственной базой данных Supabase и вашим iPhone по защищенному протоколу TLS/HTTPS.
* Никаких сторонних серверов или посредников.
* В любой момент вы можете удалить сообщения как из интерфейса iOS, так и из базы данных Supabase.
