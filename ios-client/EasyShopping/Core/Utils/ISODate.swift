import Foundation

/// ISO8601 时间解析（服务端 `Instant` 序列化，可能带毫秒也可能不带）。
enum ISODate {

    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    /// 解析 ISO8601 字符串（带/不带毫秒、甚至纳秒精度都兼容）；失败返回 nil。
    static func parse(_ text: String?) -> Date? {
        guard let text = text, !text.isEmpty else { return nil }
        if let date = fractional.date(from: text) ?? plain.date(from: text) {
            return date
        }
        // 兼容纳秒精度（Instant 序列化可能带 9 位小数，ISO8601DateFormatter 只认 3 位）
        if let dotIndex = text.firstIndex(of: ".") {
            let head = text[text.startIndex..<dotIndex]
            let tail = text[text.index(after: dotIndex)...]
            // 截掉多余小数位：取小数前 3 位
            let fraction = String(tail.prefix(3))
            let rest = tail.drop(while: { $0.isNumber })  // 丢弃原小数位
            let rebuilt = "\(head).\(fraction)\(rest)"
            return fractional.date(from: rebuilt)
        }
        return nil
    }

    /// 本地化展示（订单创建时间等）
    static func display(_ text: String?) -> String {
        guard let date = parse(text) else { return text ?? "" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f.string(from: date)
    }
}
