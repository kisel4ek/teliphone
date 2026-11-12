// Конфигурация Supabase
const SUPABASE_URL = "https://jhjesirowlvuahlqlxqt.supabase.co";
const SUPABASE_ANON_KEY = "sb_publishable_qGNun5qq4twlmJvGfX1WsA_vl_udqqq";

let messages = [];
let activeFilter = "all";
let searchQuery = "";
let socket = null;
let heartbeatInterval = null;

// Инициализация звука уведомления (Web Audio API без внешних файлов)
function playNotificationChime() {
  try {
    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();

    osc.type = "sine";
    osc.frequency.setValueAtTime(587.33, audioCtx.currentTime); // D5
    osc.frequency.exponentialRampToValueAtTime(880, audioCtx.currentTime + 0.15); // A5

    gain.gain.setValueAtTime(0.2, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.35);

    osc.connect(gain);
    gain.connect(audioCtx.destination);

    osc.start();
    osc.stop(audioCtx.currentTime + 0.35);

    // Тактильный отклик (iPhone вибро)
    if (navigator.vibrate) {
      navigator.vibrate([100, 50, 100]);
    }
  } catch (e) {
    console.log("Audio play error", e);
  }
}

// Загрузка истории сообщений (REST)
async function fetchMessages() {
  const statusDot = document.getElementById("statusDot");
  const statusText = document.getElementById("statusText");

  try {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/messages?order=received_at.desc&limit=100`, {
      headers: {
        "apikey": SUPABASE_ANON_KEY,
        "Authorization": `Bearer ${SUPABASE_ANON_KEY}`,
        "Content-Type": "application/json"
      }
    });

    if (res.ok) {
      messages = await res.json();
      renderMessages();
    } else {
      console.error("Ошибка загрузки сообщений", res.status);
    }
  } catch (err) {
    console.error("Сбой сети", err);
  }
}

// Подключение к Supabase Realtime (WebSocket)
function connectRealtime() {
  const host = new URL(SUPABASE_URL).host;
  const wsUrl = `wss://${host}/realtime/v1/websocket?apikey=${SUPABASE_ANON_KEY}&vsn=1.0.0`;

  const statusDot = document.getElementById("statusDot");
  const statusText = document.getElementById("statusText");

  if (socket) {
    socket.close();
  }

  socket = new WebSocket(wsUrl);

  socket.onopen = () => {
    statusDot.classList.add("online");
    statusText.textContent = "Live";

    // Подписываемся на INSERT таблицы messages
    const joinMsg = {
      topic: "realtime:public:messages",
      event: "phx_join",
      payload: {
        config: {
          broadcast: { self: false },
          presence: { key: "" },
          postgres_changes: [{ event: "INSERT", schema: "public", table: "messages" }]
        }
      },
      ref: "1"
    };
    socket.send(JSON.stringify(joinMsg));

    // Heartbeat каждые 25 сек
    clearInterval(heartbeatInterval);
    heartbeatInterval = setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({
          topic: "phoenix",
          event: "heartbeat",
          payload: {},
          ref: Date.now().toString()
        }));
      }
    }, 25000);
  };

  socket.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data);
      if (msg.event === "postgres_changes") {
        const newRecord = msg.payload?.data?.record;
        if (newRecord && !messages.some(m => m.id === newRecord.id)) {
          messages.unshift(newRecord);
          renderMessages();
          playNotificationChime();

          // Если включены уведомления браузера
          if (Notification.permission === "granted") {
            new Notification(newRecord.source === "sms" ? `SMS от ${newRecord.sender}` : newRecord.sender, {
              body: newRecord.body,
              icon: "./icon.svg"
            });
          }
        }
      }
    } catch (e) {
      console.error("WebSocket message parse error", e);
    }
  };

  socket.onclose = () => {
    statusDot.classList.remove("online");
    statusText.textContent = "Подключение...";
    clearInterval(heartbeatInterval);
    // Авто-переподключение через 3 секунды
    setTimeout(connectRealtime, 3000);
  };

  socket.onerror = (err) => {
    console.error("WebSocket error", err);
    socket.close();
  };
}

// Определение кода подтверждения (2FA / OTP)
function extractCode(text) {
  const match = text.match(/\b\d{4,8}\b/);
  return match ? match[0] : null;
}

// Форматирование даты
function formatDate(isoString) {
  try {
    const d = new Date(isoString);
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  } catch {
    return "";
  }
}

// Рендеринг списка сообщений
function renderMessages() {
  const container = document.getElementById("messagesList");
  if (!container) return;

  const filtered = messages.filter(m => {
    // Фильтр по типу
    if (activeFilter === "sms" && m.source !== "sms") return false;
    if (activeFilter === "notif" && m.source === "sms") return false;

    // Поиск
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      const inSender = (m.sender || "").toLowerCase().includes(q);
      const inBody = (m.body || "").toLowerCase().includes(q);
      return inSender || inBody;
    }
    return true;
  });

  if (filtered.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">📬</div>
        <h3>Нет сообщений</h3>
        <p>Новые SMS и уведомления с Android телефона появятся здесь автоматически.</p>
      </div>
    `;
    return;
  }

  container.innerHTML = filtered.map(m => {
    const isSms = m.source === "sms";
    const iconClass = isSms ? "source-sms" : "source-notif";
    const iconText = isSms ? "💬" : "🔔";
    const code = extractCode(m.body);

    return `
      <div class="message-card">
        <div class="card-header">
          <div class="sender-group">
            <div class="source-icon ${iconClass}">${iconText}</div>
            <div class="sender-name">${escapeHtml(m.sender)}</div>
          </div>
          <div class="time-stamp">${formatDate(m.received_at)}</div>
        </div>
        <div class="message-body">${escapeHtml(m.body)}</div>
        ${code ? `
          <button class="code-chip" onclick="copyCode(this, '${code}')">
            <span>📋 Скопировать код: <strong>${code}</strong></span>
          </button>
        ` : ""}
      </div>
    `;
  }).join("");
}

// Копирование кода в буфер обмена
window.copyCode = function(button, code) {
  navigator.clipboard.writeText(code).then(() => {
    const oldHtml = button.innerHTML;
    button.classList.add("copied");
    button.innerHTML = "<span>✓ Скопировано!</span>";
    if (navigator.vibrate) navigator.vibrate(50);
    setTimeout(() => {
      button.classList.remove("copied");
      button.innerHTML = oldHtml;
    }, 2000);
  });
};

function escapeHtml(str) {
  if (!str) return "";
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

// Включение Push-уведомлений в Safari iOS
window.requestPushPermission = async function() {
  if (!("Notification" in window)) {
    alert("Уведомления не поддерживаются в этом браузере.");
    return;
  }

  const permission = await Notification.requestPermission();
  if (permission === "granted") {
    document.getElementById("pushBanner").style.display = "none";
    new Notification("Teliphone", {
      body: "Уведомления успешно подключены!",
      icon: "./icon.svg"
    });
  } else {
    alert("Разрешение на уведомления не было предоставлено.");
  }
};

// События интерфейса
document.addEventListener("DOMContentLoaded", () => {
  fetchMessages();
  connectRealtime();

  // Регистрация Service Worker
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("./sw.js").then(() => {
      console.log("Service Worker зарегистрирован");
    });
  }

  // Скрытие баннера, если пуши уже разрешены
  if (window.Notification && Notification.permission === "granted") {
    const banner = document.getElementById("pushBanner");
    if (banner) banner.style.display = "none";
  }

  // Поиск
  const searchInput = document.getElementById("searchInput");
  if (searchInput) {
    searchInput.addEventListener("input", (e) => {
      searchQuery = e.target.value.trim();
      renderMessages();
    });
  }

  // Фильтры (Все / SMS / Уведомления)
  const buttons = document.querySelectorAll(".segment-btn");
  buttons.forEach(btn => {
    btn.addEventListener("click", () => {
      buttons.forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
      activeFilter = btn.dataset.filter;
      renderMessages();
    });
  });
});
