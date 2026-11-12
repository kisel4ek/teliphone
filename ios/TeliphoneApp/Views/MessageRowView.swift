import SwiftUI

struct MessageRowView: View {
    let message: MessageItem
    @State private var didCopyCode: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // Заголовок строки: Источник, Отправитель, Время
            HStack {
                sourceIcon
                    .foregroundColor(sourceColor)

                Text(message.sender)
                    .font(.headline)
                    .lineLimit(1)

                Spacer()

                Text(message.formattedDate)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            // Текст сообщения
            Text(message.body)
                .font(.subheadline)
                .foregroundColor(.primary)
                .lineLimit(3)

            // Если найден код подтверждения (2FA / OTP)
            if let code = message.extractedCode {
                HStack {
                    Text("Код: \(code)")
                        .font(.system(.caption, design: .monospaced))
                        .fontWeight(.bold)
                        .foregroundColor(.blue)

                    Spacer()

                    Button {
                        UIPasteboard.general.string = code
                        let generator = UINotificationFeedbackGenerator()
                        generator.notificationOccurred(.success)
                        withAnimation {
                            didCopyCode = true
                        }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            withAnimation {
                                didCopyCode = false
                            }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: didCopyCode ? "checkmark" : "doc.on.doc")
                            Text(didCopyCode ? "Скопировано!" : "Скопировать код")
                        }
                        .font(.caption)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(didCopyCode ? Color.green.opacity(0.15) : Color.blue.opacity(0.1))
                        .foregroundColor(didCopyCode ? .green : .blue)
                        .cornerRadius(6)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.top, 4)
            }
        }
        .padding(.vertical, 4)
    }

    private var sourceIcon: some View {
        Group {
            switch message.source {
            case "sms":
                Image(systemName: "message.fill")
            case "notification":
                Image(systemName: "bell.fill")
            default:
                Image(systemName: "antenna.radiowaves.left.and.right")
            }
        }
        .font(.caption)
    }

    private var sourceColor: Color {
        switch message.source {
        case "sms":
            return .green
        case "notification":
            return .orange
        default:
            return .blue
        }
    }
}
