//
//  CheckoutModels.swift
//  EasyShopping
//
//  结算页数据模型：结算商品行、下单请求/响应、优惠券。
//  说明：P0 服务端没有订单预览接口（/orders/preview），结算金额在客户端本地计算，
//  优惠券折扣通过 /coupons/preview 试算（促销降级时试算不折扣，仅展示）。
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

/// 我的优惠券（P0 券记录，GET /api/v1/coupons/my）
struct CheckoutCoupon: Codable, Identifiable, Hashable {
    let couponId: Int64
    let templateId: Int64?
    let name: String?
    let type: Int?
    let status: Int?
    let expireAt: String?

    var id: Int64 { couponId }
}

/// 优惠券试算请求（POST /api/v1/coupons/preview）
struct CouponPreviewRequest: Codable {
    let couponId: Int64
    let amount: Decimal
}

/// 优惠券试算响应
struct CouponPreviewResponse: Codable {
    let couponId: Int64?
    let templateId: Int64?
    let couponName: String?
    let totalAmount: Decimal?
    let discount: Decimal?
    let finalAmount: Decimal?
    let eligible: Bool?
    let reason: String?

    /// 是否可用于抵扣（促销降级时 eligible=false，仅展示原因）
    var usable: Bool { eligible ?? false }
}
