import Foundation

struct MessageItem: Identifiable, Codable, Equatable {
    let id: String
    let sender: String
    let body: String
    let source: String          // "sms", "notification", "test"
    let packageName: String?    // e.g. "com.whatsapp"
    let deviceId: String?       // Android device name
    let receivedAt: String
    var isRead: Bool?

    enum CodingKeys: String, CodingKey {
        case id
        case sender
        case body
        case source
        case packageName = "package_name"
        case deviceId = "device_id"
        case receivedAt = "received_at"
        case isRead = "is_read"
    }

    var formattedDate: String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var date = formatter.date(from: receivedAt)
        if date == nil {
            formatter.formatOptions = [.withInternetDateTime]
            date = formatter.date(from: receivedAt)
        }

        guard let validDate = date else { return receivedAt }

        let displayFormatter = DateFormatter()
        displayFormatter.dateStyle = .short
        displayFormatter.timeStyle = .medium
        displayFormatter.locale = Locale(identifier: "ru_RU")
        return displayFormatter.string(from: validDate)
    }

    /// Автоматическое определение одноразового кода подтверждения (2FA / OTP)
    var extractedCode: String? {
        let pattern = #"\b\d{4,8}\b"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let nsRange = NSRange(body.startIndex..<body.endIndex, in: body)
        if let match = regex.firstMatch(in: body, range: nsRange),
           let range = Range(match.range, in: body) {
            return String(body[range])
        }
        return nil
    }
}
