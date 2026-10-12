import SwiftUI

/// 支付页：金额 + 倒计时 + 渠道选择 → mock 支付 + 状态轮询。
/// P0 为模拟支付（core-payment /payment/mock/pay），成功后进订单详情。
struct PayView: View {
    let orderId: Int64

    @EnvironmentObject private var router: AppRouter
    @StateObject private var vm: PayViewModel
    @State private var payTask: Task<Void, Never>?

    init(orderId: Int64) {
        self.orderId = orderId
        _vm = StateObject(wrappedValue: PayViewModel(orderId: orderId))
    }

    var body: some View {
        Group {
            switch vm.phase {
            case .loading:
                LoadingView(text: "正在获取订单…")
            case .waiting:
                waitingView
            case .paying:
                payingView
            case .success:
                successView
            case .timeout:
                timeoutView
            case .failed(let message):
                failedView(message)
            }
        }
        .background(AppTheme.background)
        .navigationTitle("收银台")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(vm.phase == .paying || vm.phase == .success)
        .toast($vm.message)
        .task {
            if vm.phase == .loading {
                await vm.load()
            }
        }
        .onDisappear {
            // 离开页面取消轮询中的支付流程
            payTask?.cancel()
            payTask = nil
        }
    }

    // MARK: 待支付

    private var waitingView: some View {
        ScrollView {
            VStack(spacing: 12) {
                amountCard
                channelCard
            }
            .padding(12)
        }
        .safeAreaInset(edge: .bottom) { payBar }
    }

    private var amountCard: some View {
        VStack(spacing: 8) {
            Text("订单支付")
                .font(.subheadline)
                .foregroundColor(.secondary)
            AmountText(amount: vm.payAmount, font: .system(size: 34, weight: .bold))
            if let deadline = vm.payDeadline {
                CountdownText(deadline: deadline)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            if let orderNo = vm.orderNo {
                Text("订单号 \(orderNo)")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    private var channelCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("支付方式")
                .font(.subheadline.bold())

            ForEach(PaymentChannel.allCases) { channel in
                Button {
                    vm.selectedChannel = channel
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: channel.systemImage)
                            .foregroundColor(AppTheme.primary)
                        Text(channel.title)
                            .font(.subheadline)
                            .foregroundColor(.primary)
                        Spacer()
                        Image(systemName: vm.selectedChannel == channel
                              ? "checkmark.circle.fill" : "circle")
                            .foregroundColor(vm.selectedChannel == channel
                                             ? AppTheme.primary : .secondary)
                    }
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
            }

            Text("P0 为模拟支付渠道，点击支付直接回调成功")
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    private var payBar: some View {
        Button {
            payTask = Task {
                await vm.pay()
            }
        } label: {
            Text("确认支付 \(Money.text(vm.payAmount))")
                .font(.subheadline.bold())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(AppTheme.primary)
                .foregroundColor(.white)
                .cornerRadius(22)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    // MARK: 支付中

    private var payingView: some View {
        VStack(spacing: 14) {
            ProgressView()
                .scaleEffect(1.4)
            Text("支付处理中…")
                .font(.subheadline)
                .foregroundColor(.secondary)
            Text("请勿离开页面，正在等待支付结果")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: 成功

    private var successView: some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 64))
                .foregroundColor(.green)
            Text("支付成功")
                .font(.title3.bold())
            AmountText(amount: vm.payAmount, font: .headline)

            HStack(spacing: 12) {
                Button("返回首页") {
                    router.popToRoot()
                    router.selectedTab = .home
                }
                .font(.subheadline)
                .padding(.horizontal, 24)
                .padding(.vertical, 10)
                .overlay(
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(Color.gray.opacity(0.5), lineWidth: 1)
                )
                .foregroundColor(.primary)

                Button("查看订单") {
                    router.replaceTop(with: .orderDetail(orderId: orderId))
                }
                .font(.subheadline.bold())
                .padding(.horizontal, 24)
                .padding(.vertical, 10)
                .background(AppTheme.primary)
                .foregroundColor(.white)
                .cornerRadius(18)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: 轮询超时

    private var timeoutView: some View {
        VStack(spacing: 14) {
            Image(systemName: "clock.badge.exclamationmark")
                .font(.system(size: 56))
                .foregroundColor(.orange)
            Text("支付结果确认超时")
                .font(.headline)
            Text("订单可能已支付，请到订单详情确认")
                .font(.subheadline)
                .foregroundColor(.secondary)

            HStack(spacing: 12) {
                Button("重新支付") {
                    Task { await vm.load() }
                }
                .font(.subheadline)
                .padding(.horizontal, 24)
                .padding(.vertical, 10)
                .overlay(
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(Color.gray.opacity(0.5), lineWidth: 1)
                )
                .foregroundColor(.primary)

                Button("订单详情") {
                    router.replaceTop(with: .orderDetail(orderId: orderId))
                }
                .font(.subheadline.bold())
                .padding(.horizontal, 24)
                .padding(.vertical, 10)
                .background(AppTheme.primary)
                .foregroundColor(.white)
                .cornerRadius(18)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 24)
    }

    // MARK: 失败

    private func failedView(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "xmark.circle")
                .font(.system(size: 56))
                .foregroundColor(.red)
            Text("支付失败")
                .font(.headline)
            // 服务端 msg 直显（订单已关闭/参数错误等）
            Text(message)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            HStack(spacing: 12) {
                Button("返回") {
                    router.pop()
                }
                .font(.subheadline)
                .padding(.horizontal, 24)
                .padding(.vertical, 10)
                .overlay(
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(Color.gray.opacity(0.5), lineWidth: 1)
                )
                .foregroundColor(.primary)

                Button("重试") {
                    Task { await vm.load() }
                }
                .font(.subheadline.bold())
                .padding(.horizontal, 24)
                .padding(.vertical, 10)
                .background(AppTheme.primary)
                .foregroundColor(.white)
                .cornerRadius(18)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 24)
    }
}
