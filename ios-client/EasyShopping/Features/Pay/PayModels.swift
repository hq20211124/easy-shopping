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
///
/// 逐字段后端出处（契约真值，勿凭猜测改类型）：
/// `server/core-payment/src/main/java/com/payment/web/dto/PrepayResponse.java:17-18`
/// ```java
/// public record PrepayResponse(Long paymentId, Integer channel, BigDecimal amount, String payType,
///                              Map<String,String> payParams, Long expireAt) {}
/// ```
/// 组装点：`server/core-payment/.../service/PaymentService.java` 的 `toPrepayResponse()`。
///
/// - `payParams`：`PaymentService#payParamsOf` 的三个 switch 分支**全部** `Map.of(...)`
///   返回至少 2 个键，**永远非 null** → 声明为**非可选** `[String: String]`；
/// - `expireAt`：`toPrepayResponse()` 以 `.getEpochSecond()` 下发（**epoch 秒的 Long**），
///   不是 ISO-8601 字符串 → `Int64?`，展示请走 `expireAtDate`。
struct PrepayResponse: Decodable {
    /// Java `Long paymentId`（雪花 ID，序列左移 1bit → 偶数；勿转 Double，避免 JS/Swift 精度丢失）
    let paymentId: Int64?
    /// Java `Integer channel`（1支付宝 / 2微信 / 3银行卡 / 4余额；`PaymentService#prepay` 校验 1~4）
    let channel: Int?
    /// Java `BigDecimal amount`（服务端按订单应付金额定额，裁决 C7 防改价；
    /// 无自定义 Jackson 配置 → 序列化为 JSON **number**，故用 `Decimal` 而非 String）
    let amount: Decimal?
    /// Java `String payType`（`PaymentService#payTypeOf`：alipay_mock / wechat_mock /
    /// bank_card_mock / balance_mock / unknown）
    let payType: String?
    /// Java `Map<String,String> payParams`（P0 mock 渠道占位：支付宝 {tradeNO,mock}、
    /// 微信 {package,nonceStr,signType,timeStamp,mock}、其余 {prepayId,mock}）
    let payParams: [String: String]
    /// Java `Long expireAt` —— 支付过期**epoch 秒**（由订单 `pay_deadline` 派生，
    /// 缺失时兜底 `PaymentService.EXPIRE_FALLBACK`；客户端禁止硬编码超时分钟数 —— 裁决 C7）
    let expireAt: Int64?

    /// `expireAt` → 本地 `Date`；后端未下发时返回 nil（调用方须降级展示，不要猜分钟数）
    var expireAtDate: Date? {
        guard let expireAt = expireAt else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(expireAt))
    }
}

/// POST /api/v1/payment/mock/pay 响应
/// 后端出处 `server/core-payment/.../web/dto/MockPayResponse.java:4` → `record MockPayResponse(Long paymentId, String status)`
struct MockPayResponse: Decodable {
    let paymentId: Int64?
    /// `PaymentOrder.statusText()`：pending/paid/refunding/refunded
    let status: String?
}

/// GET /api/v1/payment/status/{orderId} 响应
/// status: pending / paid / refunding / refunded
/// 后端出处 `server/core-payment/.../web/dto/PaymentStatusResponse.java:10-11`
struct PaymentStatusResponse: Decodable {
    let orderId: Int64?
    let paymentId: Int64?
    let channel: Int?
    let amount: Decimal?
    let status: String?
    /// Java `String paidAt`（`Instant.toString()` → ISO-8601 字符串，可能 null）
    let paidAt: String?
}

/// POST /api/v1/payment/prepay 请求
struct PrepayRequest: Encodable {
    let orderId: Int64
    let channel: Int
}
