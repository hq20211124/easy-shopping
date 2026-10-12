import SwiftUI
import UIKit

/// 主题与金额格式化（金额一律 Decimal，展示两位小数，禁用 Double —— DEV.md 4.3 同口径）。
enum AppTheme {
    /// 品牌主色（与 Assets 里的 AccentColor 保持一致）
    static let primary = Color(red: 1.0, green: 0.23, blue: 0.19)
    /// 价格色
    static let price = Color(red: 1.0, green: 0.23, blue: 0.19)
    /// 页面底色
    static let background = Color(uiColor: .systemGroupedBackground)
    /// 卡片底色
    static let card = Color(uiColor: .secondarySystemGroupedBackground)
    /// 主文案 / 次要文案
    static let title = Color.primary
    static let subtitle = Color.secondary

    static let cornerRadius: CGFloat = 10
    static let padding: CGFloat = 12
}

/// 金额格式化：`¥` + 千分位 + 固定两位小数。
enum Money {
    private static let formatter: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        return f
    }()

    /// 金额文本（nil 按 0 处理，服务端价格服务降级时会返回 null）
    static func text(_ value: Decimal?) -> String {
        let amount = value ?? 0
        let number = NSDecimalNumber(decimal: amount)
        let text = formatter.string(from: number) ?? "0.00"
        return "¥\(text)"
    }
}
