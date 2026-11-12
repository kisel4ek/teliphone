import Foundation
import Combine
import SwiftUI

@MainActor
class SupabaseService: ObservableObject {
    static let shared = SupabaseService()

    @Published var messages: [MessageItem] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String? = nil
    @Published var isConnectedRealtime: Bool = false

    @AppStorage("supabase_url") var supabaseUrl: String = "https://jhjesirowlvuahlqlxqt.supabase.co"
    @AppStorage("supabase_key") var supabaseKey: String = "sb_publishable_qGNun5qq4twlmJvGfX1WsA_vl_udqqq"
    @AppStorage("device_token") var savedDeviceToken: String = ""

    private var webSocketTask: URLSessionWebSocketTask?
    private var pingTimer: Timer?

    init() {
        if isConfigured {
            Task {
                await fetchMessages()
                connectRealtime()
            }
        }
    }

    var isConfigured: Bool {
        !supabaseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !supabaseKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var cleanUrl: String {
        supabaseUrl.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    // MARK: - Загрузка сообщений (REST)
    func fetchMessages() async {
        guard isConfigured else { return }
        isLoading = true
        errorMessage = nil

        guard let url = URL(string: "\(cleanUrl)/rest/v1/messages?order=received_at.desc&limit=150") else {
            errorMessage = "Некорректный Supabase URL"
            isLoading = false
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.addValue(supabaseKey, forHTTPHeaderField: "apikey")
        request.addValue("Bearer \(supabaseKey)", forHTTPHeaderField: "Authorization")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpRes = response as? HTTPURLResponse, (200...299).contains(httpRes.statusCode) {
                let decoded = try JSONDecoder().decode([MessageItem].self, from: data)
                self.messages = decoded
            } else {
                let body = String(data: data, encoding: .utf8) ?? "Неизвестная ошибка"
                self.errorMessage = "Ошибка сервера: \(body)"
            }
        } catch {
            self.errorMessage = "Ошибка подключения: \(error.localizedDescription)"
        }
        isLoading = false
    }

    // MARK: - Удаление сообщения
    func deleteMessage(id: String) async {
        guard isConfigured, let url = URL(string: "\(cleanUrl)/rest/v1/messages?id=eq.\(id)") else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.addValue(supabaseKey, forHTTPHeaderField: "apikey")
        request.addValue("Bearer \(supabaseKey)", forHTTPHeaderField: "Authorization")

        do {
            let (_, res) = try await URLSession.shared.data(for: request)
            if let http = res as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                self.messages.removeAll(where: { $0.id == id })
            }
        } catch {
            print("Delete error: \(error)")
        }
    }

    // MARK: - Регистрация APNs токена для пушей
    func registerDeviceToken(_ token: String) async {
        savedDeviceToken = token
        guard isConfigured else { return }

        guard let url = URL(string: "\(cleanUrl)/rest/v1/devices") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.addValue(supabaseKey, forHTTPHeaderField: "apikey")
        request.addValue("Bearer \(supabaseKey)", forHTTPHeaderField: "Authorization")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.addValue("resolution=merge-duplicates", forHTTPHeaderField: "Prefer")

        let payload: [String: Any] = [
            "device_token": token,
            "platform": "ios"
        ]

        guard let bodyData = try? JSONSerialization.data(withJSONObject: payload) else { return }
        request.httpBody = bodyData

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                print("Device token registered in Supabase successfully!")
            }
        } catch {
            print("Failed to register device token: \(error)")
        }
    }

    // MARK: - Подключение к Supabase Realtime (WebSocket)
    func connectRealtime() {
        disconnectRealtime()
        guard isConfigured else { return }

        // Извлекаем host из https URL (например, xyzcompany.supabase.co)
        guard let httpUrl = URL(string: cleanUrl), let host = httpUrl.host else { return }
        let wsUrlString = "wss://\(host)/realtime/v1/websocket?apikey=\(supabaseKey)&vsn=1.0.0"
        guard let wsUrl = URL(string: wsUrlString) else { return }

        webSocketTask = URLSession.shared.webSocketTask(with: wsUrl)
        webSocketTask?.resume()

        listenForMessages()
        joinChannel()
        startPingTimer()
        isConnectedRealtime = true
    }

    func disconnectRealtime() {
        pingTimer?.invalidate()
        pingTimer = nil
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
        isConnectedRealtime = false
    }

    private func joinChannel() {
        // Подписываемся на события INSERT таблицы messages
        let joinMessage: [String: Any] = [
            "topic": "realtime:public:messages",
            "event": "phx_join",
            "payload": [
                "config": [
                    "broadcast": ["self": false],
                    "presence": ["key": ""],
                    "postgres_changes": [
                        [
                            "event": "INSERT",
                            "schema": "public",
                            "table": "messages"
                        ]
                    ]
                ]
            ],
            "ref": "1"
        ]

        sendJson(joinMessage)
    }

    private func sendJson(_ dict: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let jsonString = String(data: data, encoding: .utf8) else { return }
        webSocketTask?.send(.string(jsonString)) { error in
            if let error = error {
                print("WebSocket send error: \(error)")
            }
        }
    }

    private func listenForMessages() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .failure(let error):
                print("WebSocket receive error: \(error)")
                Task { @MainActor in
                    self.isConnectedRealtime = false
                }
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleWebSocketMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleWebSocketMessage(text)
                    }
                @unknown default:
                    break
                }
                self.listenForMessages()
            }
        }
    }

    private func handleWebSocketMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let event = json["event"] as? String else { return }

        if event == "postgres_changes" {
            if let payload = json["payload"] as? [String: Any],
               let record = payload["data"] as? [String: Any],
               let recordData = try? JSONSerialization.data(withJSONObject: record["record"] ?? [:]),
               let newMessage = try? JSONDecoder().decode(MessageItem.self, from: recordData) {
                Task { @MainActor in
                    if !self.messages.contains(where: { $0.id == newMessage.id }) {
                        withAnimation {
                            self.messages.insert(newMessage, at: 0)
                        }
                    }
                }
            }
        }
    }

    private func startPingTimer() {
        pingTimer = Timer.scheduledTimer(withTimeInterval: 25, repeats: true) { [weak self] _ in
            let heartbeat: [String: Any] = [
                "topic": "phoenix",
                "event": "heartbeat",
                "payload": [:],
                "ref": UUID().uuidString
            ]
            self?.sendJson(heartbeat)
        }
    }
}
