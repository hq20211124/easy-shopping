import Foundation

/// 支付页流程状态
enum PayPhase: Hashable {
    /// 准备中（拉订单详情）
    case loading
    /// 待支付
    case waiting
    /// 支付中（mock 支付 + 轮询）
    case paying
    /// 支付成功
    case success
    /// 轮询超时（订单可能已支付，回订单详情可确认）
    case timeout
    case failed(String)
}

/// 支付逻辑：拉订单 → 预支付 → mock 支付 → 轮询支付状态（1s/2s/4s 退避，30s 上限）。
@MainActor
final class PayViewModel: ObservableObject {

    @Published var phase: PayPhase = .loading
    @Published var payAmount: Decimal?
    @Published var payDeadline: String?
    @Published var orderNo: String?
    @Published var selectedChannel: PaymentChannel = .alipay
    @Published var message: String?

    let orderId: Int64

    init(orderId: Int64) {
        self.orderId = orderId
    }

    // MARK: - 加载订单

    func load() async {
        phase = .loading
        do {
            let order: OrderDetail = try await APIClient.shared.request(
                "/api/v1/orders/\(orderId)"
            )
            payAmount = order.payableAmount
            payDeadline = order.payDeadline
            orderNo = order.orderNo

            // 已支付订单直接进成功态
            if order.status == OrderStatus.paid.rawValue {
                phase = .success
            } else {
                phase = .waiting
            }
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    // MARK: - 支付（可在失败/超时后重试）

    func pay() async {
        switch phase {
        case .waiting, .failed, .timeout:
            break
        default:
            return
        }
        phase = .paying

        // 1. 预支付拿 paymentId
        let paymentId: Int64
        do {
            let prepay: PrepayResponse = try await APIClient.shared.request(
                "/api/v1/payment/prepay",
                method: .post,
                body: PrepayRequest(orderId: orderId, channel: selectedChannel.rawValue)
            )
            guard let id = prepay.paymentId else {
                phase = .failed("预支付失败：未返回 paymentId")
                return
            }
            paymentId = id
        } catch {
            // 服务端 msg 直显（如订单已关闭）
            phase = .failed(error.localizedDescription)
            return
        }

        // 2. mock 支付（P0 模拟支付渠道，见 core-payment README）
        do {
            struct MockPayBody: Encodable { let paymentId: Int64 }
            let _: MockPayResponse = try await APIClient.shared.request(
                "/api/v1/payment/mock/pay",
                method: .post,
                body: MockPayBody(paymentId: paymentId)
            )
        } catch {
            phase = .failed(error.localizedDescription)
            return
        }

        // 3. 轮询支付状态
        await poll()
    }

    /// 轮询：1s → 2s → 4s 封顶，累计 30s 未 paid 判超时。
    /// 页面退出时 Task 被取消，循环随之终止。
    private func poll() async {
        var delay: UInt64 = 1_000_000_000
        let deadline = Date().addingTimeInterval(30)

        while !Task.isCancelled && Date() < deadline {
            do {
                let status: PaymentStatusResponse = try await APIClient.shared.request(
                    "/api/v1/payment/status/\(orderId)"
                )
                if status.status == "paid" {
                    phase = .success
                    await AppState.shared.refreshCartCount()
                    return
                }
                if status.status == "refunded" || status.status == "closed" {
                    phase = .failed("支付状态异常：\(status.status ?? "")")
                    return
                }
            } catch {
                // 单次轮询失败忽略，继续下一轮
            }

            try? await Task.sleep(nanoseconds: delay)
            delay = min(delay * 2, 4_000_000_000)
        }

        if !Task.isCancelled {
            phase = .timeout
        }
    }

    // MARK: - 重试

    func retry() async {
        switch phase {
        case .failed, .timeout:
            await load()
        default:
            break
        }
    }
}
