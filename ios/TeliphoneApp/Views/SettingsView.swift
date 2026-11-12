import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var supabase: SupabaseService
    @Environment(\.dismiss) private var dismiss

    @State private var urlInput: String = ""
    @State private var keyInput: String = ""
    @State private var copiedToken: Bool = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://xyz.supabase.co", text: $urlInput)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                        .keyboardType(.URL)

                    SecureField("Anon Public Key", text: $keyInput)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                } header: {
                    Text("Параметры Supabase")
                } footer: {
                    Text("Скопируйте URL и публичный anon-ключ из панели управления Supabase -> Settings -> API.")
                }

                Section("Статус подключения") {
                    HStack {
                        Text("Realtime WebSocket")
                        Spacer()
                        HStack(spacing: 6) {
                            Circle()
                                .fill(supabase.isConnectedRealtime ? Color.green : Color.red)
                                .frame(width: 10, height: 10)
                            Text(supabase.isConnectedRealtime ? "Подключено" : "Отключено")
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }

                    Button("Переподключить Realtime") {
                        supabase.connectRealtime()
                    }
                }

                Section {
                    if supabase.savedDeviceToken.isEmpty {
                        Text("Токен APNs еще не получен от Apple. Запустите на реальном iPhone.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    } else {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(supabase.savedDeviceToken)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(.secondary)
                                .lineLimit(3)

                            Button {
                                UIPasteboard.general.string = supabase.savedDeviceToken
                                copiedToken = true
                                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                    copiedToken = false
                                }
                            } label: {
                                Label(copiedToken ? "Токен скопирован!" : "Скопировать APNs токен",
                                      systemImage: copiedToken ? "checkmark" : "doc.on.doc")
                                    .font(.footnote)
                            }
                        }
                    }
                } header: {
                    Text("Apple Push (APNs) токен")
                } footer: {
                    Text("Этот токен сохраняется в Supabase таблице 'devices' для отправки системных Push-уведомлений.")
                }

                Section {
                    Button("Сохранить и обновить") {
                        supabase.supabaseUrl = urlInput
                        supabase.supabaseKey = keyInput
                        Task {
                            await supabase.fetchMessages()
                            supabase.connectRealtime()
                            if !supabase.savedDeviceToken.isEmpty {
                                await supabase.registerDeviceToken(supabase.savedDeviceToken)
                            }
                        }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
            .navigationTitle("Настройки")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Закрыть") {
                        dismiss()
                    }
                }
            }
            .onAppear {
                urlInput = supabase.supabaseUrl
                keyInput = supabase.supabaseKey
            }
        }
    }
}
