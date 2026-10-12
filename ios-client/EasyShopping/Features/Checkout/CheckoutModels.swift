//
//  CheckoutModels.swift
//  EasyShopping
//
//  结算页数据模型：结算商品行、下单请求/响应、优惠券。
//  说明：P0 服务端没有订单预览接口（/orders/preview），结算金额在客户端本地计算，
//  优惠券折扣通过 /coupons/preview 试算（促销降级时试算不折扣，仅展示）。
//
//  ⚠️ 契约纪律（本文件所有类型均以 server 源码为准，逐字段出处见各处注释）：
//  1. 金额一律 `Decimal`（后端 BigDecimal → JSON number），禁 Double；
//  2. 时间一律 `String?` + `ISODate` 解析（后端 Instant → ISO-8601）；
//  3. 后端下发的枚举**字符串不得当数字解**（券 status 是英文串，见 CouponStatus）；
//  4. 请求体结构必须逐字段对齐后端 record（券试算 amount 是**数组**，不是标量）。
//

import Foundation

/// 结算商品行（来自购物车勾选 或 立即购买）
struct CheckoutItem: Hashable {
    let skuId: Int64
    var quantity: Int
    var title: String
    var image: String
    var price: Decimal?
    var fromCart: Bool
    /// 购物车行 ID（立即购买为 nil），P0 下单不回传，仅用于展示区分
    var cartItemId: Int64? = nil
}

/// 创建订单请求（POST /api/v1/orders）
struct CreateOrderRequest: Codable {
    let items: [CreateOrderItem]
    let addressId: Int64?
    let receiverName: String?
    let receiverMobile: String?
    let receiverAddress: String?
    let couponId: Int64?
    let remark: String?
    /// 来源（服务端 Integer，默认 1；iOS 固定传 1）
    let source: Int?

    struct CreateOrderItem: Codable {
        let skuId: Int64
        let quantity: Int
    }
}

/// 创建订单响应（CreateOrderVO 子集）
struct CreateOrderResponse: Codable {
    let orderId: Int64
    let orderNo: String?
    let status: Int?
    let statusText: String?
    let totalAmount: Decimal?
    let discountAmount: Decimal?
    let freightAmount: Decimal?
    let payAmount: Decimal?
    let expireSeconds: Int?
    let payDeadline: String?
}

// MARK: - 优惠券状态（唯一映射点）

/// 用户券记录状态。
///
/// 后端下发的是**英文字符串**，不是数字：
/// - 组装 `server/core-promotion/.../service/CouponService.java` 的 `toRecordResponse()`
///   → `r.statusText()`；
/// - 定义 `server/core-promotion/.../domain/CouponRecord.java` 的 `statusText()`
///   （0可用→`"available"`、1已使用→`"used"`、2已过期→`"expired"`、default→`"unknown"`）；
/// - 出参类型 `.../web/dto/CouponRecordResponse.java:11` 的 `String status`；
/// - 实测断言 `server/core-promotion/.../src/test/java/com/promotion/CouponIntegrationTest.java`
///   的 `.andExpect(jsonPath("$.data.status").value("available"))`；
///
/// ⚠️ 曾经声明成 `Int?` 会让**整包解码失败**（JSONDecoder 对 `"available"` 解 Int 抛 typeMismatch），
/// 再被 `APIClient` 的静默解码兜底吞掉 → 结算页优惠券区恒为空。
enum CouponStatus: String, Hashable {
    case available
    case used
    case expired
    /// 后端 `statusText()` 的 default 分支（`CouponRecord#statusText()` 的 default 分支），或客户端未识别的串
    case unknown

    /// 本地展示文案（后端只给英文串，界面上要中文）
    var text: String {
        switch self {
        case .available: return "可用"
        case .used: return "已使用"
        case .expired: return "已过期"
        case .unknown: return "未知状态"
        }
    }

    /// 是否处于"未核销且未过期"的展示态。
    /// 注意：这只用于**列表高亮/置灰**；某张券在本次订单上到底能不能抵扣，
    /// 唯一权威是服务端试算 `POST /api/v1/coupons/preview` 的 `eligible`。
    var isUsable: Bool { self == .available }
}

/// 我的优惠券（GET /api/v1/coupons/my → core-promotion CouponRecordResponse）
///
/// 逐字段后端出处：`server/core-promotion/src/main/java/com/promotion/web/dto/CouponRecordResponse.java:11-13`
/// ```java
/// public record CouponRecordResponse(Long couponId, Long templateId, String name, Integer type,
///                                    String status, Instant expireAt, Instant receivedAt,
///                                    Long usedOrderId, Instant usedTime) {}
/// ```
/// - `couponId` 是**用户券记录 ID**（"我的哪张券"，试算/核销入参），
///   `templateId` 才是**券模板 ID**（领券 receive 入参）——见该 DTO javadoc:8-10；
/// - `status` 是英文串（`String`），**不是 0/1/2**；
/// - `expireAt` / `receivedAt` 是 `Instant` → Spring Boot 默认序列化为 ISO-8601 字符串。
struct CheckoutCoupon: Decodable, Identifiable, Hashable {
    /// 用户券记录 ID（preview / deduct / release 的 couponId 入参）
    let couponId: Int64
    /// 券模板 ID（receive 领券入参；`CouponService#my` 在模板被删时下发 null）
    let templateId: Int64?
    /// 券名称（模板被删时为 null）
    let name: String?
    /// 1满减 / 2折扣 / 3无门槛（`CouponTemplate.TYPE_FULL_CUT/DISCOUNT/NO_THRESHOLD`）
    let type: Int?
    /// 英文状态串：`available` / `used` / `expired` / `unknown`
    let status: String?
    /// 券过期时间（Instant → ISO-8601 字符串）
    let expireAt: String?

    var id: Int64 { couponId }

    /// 集中映射点：缺失/未识别的串一律降级 `.unknown`，页面永不错字崩溃（不做散落字符串比较）
    var statusValue: CouponStatus { CouponStatus(rawValue: status ?? "") ?? .unknown }

    /// 展示名兜底（模板被删时 name 为 null）
    var displayName: String { name ?? "优惠券" }
}

/// 优惠券试算请求（POST /api/v1/coupons/preview）
///
/// 后端出处：`server/core-promotion/src/main/java/com/promotion/web/dto/PreviewRequest.java:14-15`
/// ```java
/// public record PreviewRequest(@NotNull Long couponId,
///                              @NotNull List<@NotNull BigDecimal> amount) {}
/// ```
/// - `couponId` = **用户券记录 ID**（`CouponService#preview` 的形参名就是 `recordId`，
///   且由 `requireOwnedRecord()` 校验归属），**不是券模板 ID**；
/// - `amount` 是**数组**：逐行商品金额，服务端 `CouponService#preview` 求和后再按
///   门槛/面额试算（`amounts` 为空直接抛 400）。
///   传标量会被 Jackson 判 `MismatchedInputException` → 400 → 试算恒失败。
/// - 元素为非负金额；`Decimal` 经 `JSONEncoder` 序列化为 JSON **number**，
///   与服务端 `BigDecimal` 反序列化匹配（无自定义 Jackson 配置，BigDecimal 默认就是 number，
///   佐证 `core-payment` 的 `PaymentIntegrationTest` 用 `jsonPath("$.data.amount").value(...)` 断言 BigDecimal 走 number）。
struct CouponPreviewRequest: Encodable {
    let couponId: Int64
    /// 逐行商品金额列表（对齐 web-buyer 结算页的 `rows.map(lineTotal)` 逐行口径）
    let amount: [Decimal]
}

/// 优惠券试算响应
///
/// 后端出处：`server/core-promotion/src/main/java/com/promotion/web/dto/PreviewResponse.java:12-14`
/// ```java
/// public record PreviewResponse(Long couponId, Long templateId, String couponName,
///                               BigDecimal totalAmount, BigDecimal discount, BigDecimal finalAmount,
///                               boolean eligible, String reason) {}
/// ```
/// `eligible=false` 时后端**不抛异常**而是 `discount=0` + `reason` 说明原因
/// （`CouponService#unavailable(...)`），便于前端展示"不可用原因"。
struct CouponPreviewResponse: Decodable {
    let couponId: Int64?
    let templateId: Int64?
    let couponName: String?
    /// 服务端求和后的订单商品金额
    let totalAmount: Decimal?
    /// 优惠金额（HALF_UP 两位小数）
    let discount: Decimal?
    /// 优惠后应付金额
    let finalAmount: Decimal?
    /// 服务端判定的可用性（注意字段名是 `eligible`，不是 `usable`）
    let eligible: Bool?
    /// 不可用原因（eligible=false 时有值）
    let reason: String?

    /// 是否可用于抵扣（`eligible` 缺省按 false 处理，绝不"缺省可用"）
    var usable: Bool { eligible ?? false }
}

/// 「选了券但券没生效」的下单确认弹窗模型（P0-4 ③）。
///
/// 背景：旧实现 `let couponId = (preview?.usable ?? false) ? selectedCouponId : nil`
/// 会让"用户看到券名、订单却不带 couponId"，按原价成交且**全程无提示**——
/// 这比报错更危险（静默数据错误）。现在改为阻断式确认：必须用户明确选择才能按原价下单。
struct CouponFallbackNotice: Identifiable, Hashable {
    /// 所选券展示名
    let couponName: String
    /// 不可用原因（试算异常 / 服务端 reason / 试算未完成）
    let reason: String

    var id: String { couponName + "|" + reason }

    var title: String { "该券不可用" }

    var message: String {
        "\(couponName)未能生效（\(reason)）。继续下单将不含优惠券，按商品原价支付。"
    }

    var confirmTitle: String { "仍按原价支付" }
    var cancelTitle: String { "返回选择优惠券" }
}
