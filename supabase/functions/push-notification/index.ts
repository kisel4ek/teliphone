// Supabase Edge Function: push-notification
// Отправляет Push-уведомление на все зарегистрированные iOS устройства при появлении нового сообщения.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// Настройки из переменных окружения Supabase (Project Settings -> Edge Functions -> Secrets)
// Либо APNs (.p8 Auth Key), либо OneSignal / FCM
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

// Apple APNs настройки
const APNS_KEY_ID = Deno.env.get("APNS_KEY_ID"); // e.g. "ABC1234567"
const APNS_TEAM_ID = Deno.env.get("APNS_TEAM_ID"); // e.g. "DEF9876543"
const APNS_BUNDLE_ID = Deno.env.get("APNS_BUNDLE_ID") || "com.teliphone.ios";
const APNS_P8_PRIVATE_KEY = Deno.env.get("APNS_P8_PRIVATE_KEY"); // Содержимое файла AuthKey_XXXXXXXXXX.p8
const APNS_PRODUCTION = Deno.env.get("APNS_PRODUCTION") === "true"; // true для App Store/TestFlight, false для Sandbox/Debug

// Альтернатива: OneSignal (если не хочется возиться с APNs ключами)
const ONESIGNAL_APP_ID = Deno.env.get("ONESIGNAL_APP_ID");
const ONESIGNAL_API_KEY = Deno.env.get("ONESIGNAL_API_KEY");

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

interface WebhookPayload {
  type: "INSERT" | "UPDATE" | "DELETE";
  table: string;
  schema: string;
  record: {
    id: string;
    sender: string;
    body: string;
    source: string;
    received_at: string;
  };
}

serve(async (req: Request) => {
  try {
    const payload: WebhookPayload = await req.json();

    // Проверяем, что событие - вставка нового сообщения
    if (payload.type !== "INSERT" || !payload.record) {
      return new Response(JSON.stringify({ message: "Not an INSERT event" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    const { sender, body, source } = payload.record;
    const title = source === "sms" ? `SMS от ${sender}` : `${sender}`;
    const truncatedBody = body.length > 120 ? body.substring(0, 117) + "..." : body;

    // 1. Получаем список всех зарегистрированных iOS токенов из таблицы devices
    const { data: devices, error } = await supabase
      .from("devices")
      .select("device_token")
      .eq("platform", "ios");

    if (error || !devices || devices.length === 0) {
      console.log("No iOS devices registered for push.");
      return new Response(JSON.stringify({ message: "No devices found" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    const tokens = devices.map((d: { device_token: string }) => d.device_token);
    console.log(`Sending push to ${tokens.length} devices...`);

    // 2. Отправка через APNs (если настроены ключи)
    if (APNS_KEY_ID && APNS_TEAM_ID && APNS_P8_PRIVATE_KEY) {
      const jwt = await generateApnsJwt(APNS_KEY_ID, APNS_TEAM_ID, APNS_P8_PRIVATE_KEY);
      const host = APNS_PRODUCTION ? "api.push.apple.com" : "api.sandbox.push.apple.com";

      const results = await Promise.allSettled(
        tokens.map(async (deviceToken) => {
          const url = `https://${host}/3/device/${deviceToken}`;
          const apnsPayload = {
            aps: {
              alert: {
                title: title,
                body: truncatedBody,
              },
              sound: "default",
              badge: 1,
            },
            data: payload.record,
          };

          const res = await fetch(url, {
            method: "POST",
            headers: {
              authorization: `bearer ${jwt}`,
              "apns-topic": APNS_BUNDLE_ID,
              "apns-push-type": "alert",
              "apns-priority": "10",
              "Content-Type": "application/json",
            },
            body: JSON.stringify(apnsPayload),
          });

          if (!res.ok) {
            const errText = await res.text();
            console.error(`APNs error for ${deviceToken}: ${res.status} ${errText}`);
            // Если токен устарел (BadDeviceToken / Unregistered), удаляем его из базы
            if (res.status === 410 || (res.status === 400 && errText.includes("BadDeviceToken"))) {
              await supabase.from("devices").delete().eq("device_token", deviceToken);
            }
          }
          return res.status;
        })
      );
      return new Response(JSON.stringify({ success: true, count: tokens.length, results }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    // 3. Отправка через OneSignal (если настроен)
    if (ONESIGNAL_APP_ID && ONESIGNAL_API_KEY) {
      const osRes = await fetch("https://onesignal.com/api/v1/notifications", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Basic ${ONESIGNAL_API_KEY}`,
        },
        body: JSON.stringify({
          app_id: ONESIGNAL_APP_ID,
          headings: { en: title, ru: title },
          contents: { en: truncatedBody, ru: truncatedBody },
          include_ios_tokens: tokens,
          data: payload.record,
        }),
      });
      const osData = await osRes.json();
      return new Response(JSON.stringify({ success: true, onesignal: osData }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    return new Response(
      JSON.stringify({
        message: "No push provider configured (set APNs or OneSignal secrets in Supabase)",
        devicesCount: tokens.length,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    console.error("Error sending push notification:", message);
    return new Response(JSON.stringify({ error: message }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});

// Генерация ES256 JWT токена для Apple APNs
async function generateApnsJwt(keyId: string, teamId: string, p8KeyPem: string): Promise<string> {
  const header = { alg: "ES256", kid: keyId };
  const now = Math.floor(Date.now() / 1000);
  const claims = { iss: teamId, iat: now };

  const encodedHeader = base64UrlEncode(JSON.stringify(header));
  const encodedClaims = base64UrlEncode(JSON.stringify(claims));
  const signingInput = `${encodedHeader}.${encodedClaims}`;

  // Очистка PEM заголовков и декодирование PKCS#8
  const cleanPem = p8KeyPem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const binaryDer = Uint8Array.from(atob(cleanPem), (c) => c.charCodeAt(0));

  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    binaryDer,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"]
  );

  const signatureBuffer = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    cryptoKey,
    new TextEncoder().encode(signingInput)
  );

  // Конвертация IEEE P1363 подписи в Base64URL
  const signatureBytes = new Uint8Array(signatureBuffer);
  const encodedSignature = base64UrlEncodeBytes(signatureBytes);

  return `${signingInput}.${encodedSignature}`;
}

function base64UrlEncode(str: string): string {
  return btoa(str).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlEncodeBytes(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
