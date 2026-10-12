import Foundation

// MARK: - 支付（core-payment P0 mock 支付）

/// 支付渠道（服务端 PrepayRequest.channel：1支付宝/2微信/3银行卡/4余额）
enum PaymentChannel: Int, CaseIterable, Identifiable, Hashable {
    case alipay = 1
    case wechat = 2
    case bankCard = 3
    case balance = 4

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .alipay: return "支付宝"
        case .wechat: return "微信支付"
        case .bankCard: return "银行卡"
        case .balance: return "余额支付"
        }
    }

    var systemImage: String {
        switch self {
        case .alipay: return "a.circle.fill"
        case .wechat: return "message.circle.fill"
        case .bankCard: return "creditcard.fill"
        case .balance: return "yensign.circle.fill"
        }
    }
}

/// POST /api/v1/payment/prepay 响应
struct PrepayResponse: Decodable {
    let paymentId: Int64?
    let channel: Int?
    let amount: Decimal?
    let payType: String?
    let payParams: String?
    let expireAt: String?
}

/// POST /api/v1/payment/mock/pay 响应
struct MockPayResponse: Decodable {
    let paymentId: Int64?
    let status: String?
}

/// GET /api/v1/payment/status/{orderId} 响应
/// status: pending / paid / refunding / refunded
struct PaymentStatusResponse: Decodable {
    let orderId: Int64?
    let paymentId: Int64?
    let channel: Int?
    let amount: Decimal?
    let status: String?
    let paidAt: String?
}

/// POST /api/v1/payment/prepay 请求
struct PrepayRequest: Encodable {
    let orderId: Int64
    let channel: Int
}
