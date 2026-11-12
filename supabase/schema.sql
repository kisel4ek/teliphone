-- 1. Таблица для хранения сообщений с Android
CREATE TABLE IF NOT EXISTS public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender TEXT NOT NULL,
    body TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'sms', -- 'sms', 'notification', etc.
    package_name TEXT,                  -- 'com.whatsapp', 'org.telegram.messenger', 'com.google.android.apps.messaging'
    device_id TEXT,                     -- Идентификатор Android устройства
    received_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

-- Индексы для быстрой фильтрации и сортировки
CREATE INDEX IF NOT EXISTS idx_messages_received_at ON public.messages(received_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON public.messages(sender);

-- 2. Таблица для хранения APNs / Push токенов iOS устройств
CREATE TABLE IF NOT EXISTS public.devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_token TEXT NOT NULL UNIQUE,
    platform TEXT NOT NULL DEFAULT 'ios',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

-- 3. Включаем Supabase Realtime для мгновенного обновления на iOS в приложении
ALTER PUBLICATION supabase_realtime ADD TABLE public.messages;

-- 4. Политики безопасности (Row Level Security)
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

-- Для простоты при использовании Anon Key (в боевом режиме можно настроить авторизацию пользователей)
CREATE POLICY "Allow anon read and insert messages"
ON public.messages
FOR ALL
TO anon
USING (true)
WITH CHECK (true);

CREATE POLICY "Allow anon manage devices"
ON public.devices
FOR ALL
TO anon
USING (true)
WITH CHECK (true);

-- 5. Триггер для отправки Push-уведомлений через Supabase Edge Function
-- Примечание: Для автоматического вызова Edge Function при добавлении сообщения
-- вы можете настроить Database Webhook в панели управления Supabase:
-- Dashboard -> Database -> Webhooks -> Create Webhook on INSERT in 'messages'
-- URL: https://<PROJECT_REF>.supabase.co/functions/v1/push-notification
