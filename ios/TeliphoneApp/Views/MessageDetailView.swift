import SwiftUI

struct MessageDetailView: View {
    let message: MessageItem
    @EnvironmentObject var supabase: SupabaseService
    @Environment(\.dismiss) private var dismiss
    @State private var copied: Bool = false

    var body: some View {
        List {
            Section("Отправитель и источник") {
                LabeledContent("Отправитель", value: message.sender)
                LabeledContent("Тип", value: message.source.uppercased())
                if let pkg = message.packageName {
                    LabeledContent("Приложение", value: pkg)
                }
                if let dev = message.deviceId {
                    LabeledContent("Устройство", value: dev)
                }
                LabeledContent("Дата и время", value: message.formattedDate)
            }

            Section("Текст сообщения") {
                Text(message.body)
                    .font(.body)
                    .textSelection(.enabled)
                    .padding(.vertical, 4)

                Button {
                    UIPasteboard.general.string = message.body
                    let generator = UINotificationFeedbackGenerator()
                    generator.notificationOccurred(.success)
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        copied = false
                    }
                } label: {
                    Label(copied ? "Текст скопирован!" : "Скопировать весь текст",
                          systemImage: copied ? "checkmark" : "doc.on.doc")
                }
            }

            if let code = message.extractedCode {
                Section("Код подтверждения") {
                    HStack {
                        Text(code)
                            .font(.system(.title2, design: .monospaced))
                            .fontWeight(.bold)
                            .foregroundColor(.blue)

                        Spacer()

                        Button("Скопировать") {
                            UIPasteboard.general.string = code
                            let generator = UINotificationFeedbackGenerator()
                            generator.notificationOccurred(.success)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
            }

            Section {
                Button(role: .destructive) {
                    Task {
                        await supabase.deleteMessage(id: message.id)
                        dismiss()
                    }
                } label: {
                    Label("Удалить сообщение", systemImage: "trash")
                }
            }
        }
        .navigationTitle("Детали сообщения")
        .navigationBarTitleDisplayMode(.inline)
    }
}
