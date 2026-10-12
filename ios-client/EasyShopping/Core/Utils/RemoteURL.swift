import Foundation

/// 字符串 URL → URL（空串 / 无 scheme 一律返回 nil，避免 AsyncImage 收到非法地址）
enum RemoteURL {
    static func make(_ text: String?) -> URL? {
        guard let text = text, !text.isEmpty,
              let url = URL(string: text),
              url.scheme != nil else {
            return nil
        }
        return url
    }
}
