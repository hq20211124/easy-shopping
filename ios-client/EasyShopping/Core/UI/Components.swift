import SwiftUI

// MARK: - 状态 Chip（订单状态 / 优惠标签等，文案以后端下发为准）

struct StatusChip: View {
    let text: String
    var color: Color = .gray

    var body: some View {
        Text(text)
            .font(.caption)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .overlay(
                RoundedRectangle(cornerRadius: 4)
                    .stroke(color, lineWidth: 1)
            )
            .foregroundColor(color)
    }
}

// MARK: - 金额文本（Decimal 两位小数）

struct AmountText: View {
    let amount: Decimal?
    var font: Font = .headline
    var color: Color = AppTheme.price
    var strikethrough: Bool = false

    var body: some View {
        Text(Money.text(amount))
            .font(font)
            .foregroundColor(color)
            .strikethrough(strikethrough)
    }
}

// MARK: - 加载 / 空态 / 错误态

struct LoadingView: View {
    var text: String = "加载中…"

    var body: some View {
        VStack(spacing: 10) {
            ProgressView()
            Text(text)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.vertical, 60)
    }
}

struct EmptyStateView: View {
    let title: String
    var systemImage: String = "tray"
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 44))
                .foregroundColor(.secondary)
            Text(title)
                .font(.subheadline)
                .foregroundColor(.secondary)
            if let actionTitle = actionTitle, let action = action {
                Button(actionTitle, action: action)
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(AppTheme.primary, lineWidth: 1)
                    )
                    .foregroundColor(AppTheme.primary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }
}

struct ErrorStateView: View {
    let message: String
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 40))
                .foregroundColor(.secondary)
            Text(message)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            if let retry = retry {
                Button("重试", action: retry)
                    .font(.subheadline)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 8)
                    .background(AppTheme.primary)
                    .foregroundColor(.white)
                    .cornerRadius(16)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 50)
    }
}

// MARK: - 数量步进器

struct QuantityStepper: View {
    @Binding var quantity: Int
    var range: ClosedRange<Int> = 1...99

    var body: some View {
        HStack(spacing: 0) {
            button(symbol: "minus", enabled: quantity > range.lowerBound) {
                if quantity > range.lowerBound { quantity -= 1 }
            }
            Text("\(quantity)")
                .font(.system(.subheadline, design: .monospaced))
                .frame(minWidth: 36)
            button(symbol: "plus", enabled: quantity < range.upperBound) {
                if quantity < range.upperBound { quantity += 1 }
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color.gray.opacity(0.4), lineWidth: 1)
        )
    }

    private func button(symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .semibold))
                .frame(width: 28, height: 28)
                .foregroundColor(enabled ? Color.primary : Color.secondary.opacity(0.4))
        }
        .disabled(!enabled)
    }
}

// MARK: - 支付倒计时（读 pay_deadline，不硬编码超时分钟数 —— 裁决 C7）

struct CountdownText: View {
    /// 服务端下发的 pay_deadline（ISO8601）
    let deadline: String?
    var prefix: String = "支付剩余 "
    var suffix: String = ""

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            Text(prefix + Self.remaining(deadline: deadline, now: context.date) + suffix)
                .font(.system(.callout, design: .monospaced))
        }
    }

    /// 剩余时间文案；未到点显示 mm:ss / h:mm:ss，已超时显示「已超时」。
    static func remaining(deadline: String?, now: Date) -> String {
        guard let date = ISODate.parse(deadline) else { return "--:--" }
        let seconds = Int(date.timeIntervalSince(now).rounded())
        if seconds <= 0 { return "已超时" }
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        }
        return String(format: "%02d:%02d", m, s)
    }
}

// MARK: - 轻提示 Toast（错误文案直显服务端 msg）

struct ToastModifier: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let message = message {
                    Text(message)
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Color.black.opacity(0.78))
                        .foregroundColor(.white)
                        .cornerRadius(8)
                        .padding(.horizontal, 40)
                        .padding(.bottom, 90)
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: message)
            .task(id: message) {
                guard message != nil else { return }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                message = nil
            }
    }
}

extension View {
    /// 轻提示：`vm.message` 变化后展示 2 秒自动消失。
    func toast(_ message: Binding<String?>) -> some View {
        modifier(ToastModifier(message: message))
    }
}
