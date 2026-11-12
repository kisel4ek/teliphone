import SwiftUI

struct ContentView: View {
    @EnvironmentObject var supabase: SupabaseService
    @State private var showingSettings: Bool = false
    @State private var searchText: String = ""
    @State private var selectedFilter: FilterType = .all

    enum FilterType: String, CaseIterable, Identifiable {
        case all = "Все"
        case sms = "SMS"
        case notif = "Уведомления"
        var id: String { rawValue }
    }

    var filteredMessages: [MessageItem] {
        supabase.messages.filter { item in
            let matchesFilter: Bool
            switch selectedFilter {
            case .all: matchesFilter = true
            case .sms: matchesFilter = item.source == "sms"
            case .notif: matchesFilter = item.source != "sms"
            }

            if !matchesFilter { return false }
            if searchText.isEmpty { return true }

            return item.sender.localizedCaseInsensitiveContains(searchText) ||
                   item.body.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Фильтр-селектор
                Picker("Фильтр", selection: $selectedFilter) {
                    ForEach(FilterType.allCases) { filter in
                        Text(filter.rawValue).tag(filter)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.top, 8)
                .padding(.bottom, 6)

                if !supabase.isConfigured {
                    notConfiguredView
                } else if supabase.isLoading && supabase.messages.isEmpty {
                    ProgressView("Загрузка сообщений...")
                        .frame(maxHeight: .infinity)
                } else if filteredMessages.isEmpty {
                    emptyMessagesView
                } else {
                    List {
                        ForEach(filteredMessages) { msg in
                            NavigationLink(destination: MessageDetailView(message: msg)) {
                                MessageRowView(message: msg)
                            }
                        }
                        .onDelete { indexSet in
                            for index in indexSet {
                                let id = filteredMessages[index].id
                                Task {
                                    await supabase.deleteMessage(id: id)
                                }
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                    .refreshable {
                        await supabase.fetchMessages()
                    }
                }
            }
            .navigationTitle("Сообщения")
            .searchable(text: $searchText, prompt: "Поиск по номеру или тексту")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(supabase.isConnectedRealtime ? Color.green : Color.orange)
                            .frame(width: 8, height: 8)
                        Text(supabase.isConnectedRealtime ? "Live" : "Offline")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showingSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
        }
    }

    private var notConfiguredView: some View {
        VStack(spacing: 16) {
            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                .font(.system(size: 50))
                .foregroundColor(.secondary)

            Text("Supabase не подключен")
                .font(.title3)
                .fontWeight(.semibold)

            Text("Укажите URL проекта и публичный Anon Key в настройках, чтобы начать получать сообщения с Android.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Button("Открыть настройки") {
                showingSettings = true
            }
            .buttonStyle(.borderedProminent)
        }
        .frame(maxHeight: .infinity)
    }

    private var emptyMessagesView: some View {
        VStack(spacing: 12) {
            Image(systemName: "tray")
                .font(.system(size: 44))
                .foregroundColor(.secondary)

            Text("Нет сообщений")
                .font(.headline)
                .foregroundColor(.secondary)

            Text("Новые входящие SMS и уведомления с Android телефона появятся здесь автоматически в реальном времени.")
                .font(.footnote)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxHeight: .infinity)
    }
}
