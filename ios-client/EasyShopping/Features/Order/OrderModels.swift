import Foundation

// MARK: - 订单状态（0 基 8 值，唯一映射点：server/core-order OrderStatus + AGENTS.md 口径）

/// 订单状态枚举。任何状态判断/文案只从这里取，禁止散落魔法数。
/// 0待付款 1待发货 2待收货 3已完成 4退款中 5已退款 6已取消 7已关闭
enum OrderStatus: Int, CaseIterable, Identifiable, Hashable {
    case unpaid = 0
    case paid = 1
    case shipped = 2
    case completed = 3
    case refunding = 4
    case refunded = 5
    case cancelled = 6
    case closed = 7

    var id: Int { rawValue }

    /// 本地兜底文案（正常以服务端 statusText 为准）
    var text: String {
        switch self {
        case .unpaid: return "待付款"
        case .paid: return "待发货"
        case .shipped: return "待收货"
        case .completed: return "已完成"
        case .refunding: return "退款中"
        case .refunded: return "已退款"
        case .cancelled: return "已取消"
        case .closed: return "已关闭"
        }
    }

    init?(raw: Int?) {
        guard let raw, let value = OrderStatus(rawValue: raw) else { return nil }
        self = value
    }
}

/// 列表 Tab（P0 只覆盖 0~3；售后 4/5 服务端 status 参数为单值不支持合并查询）
enum OrderTab: Int, CaseIterable, Identifiable, Hashable {
    case all = -1
    case unpaid = 0
    case paid = 1
    case shipped = 2
    case completed = 3

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .all: return "全部"
        case .unpaid: return "待付款"
        case .paid: return "待发货"
        case .shipped: return "待收货"
        case .completed: return "已完成"
        }
    }

    /// 传给 `GET /api/v1/orders?status=` 的参数（nil = 不带参 = 全部）
    var queryValue: String? {
        rawValue < 0 ? nil : String(rawValue)
    }
}

// MARK: - 行项 / 按钮 / 明细（server OrderDtos）

/// 订单行项（OrderItemView）
struct OrderItem: Decodable, Hashable {
    let orderId: Int64?
    let orderNo: String?
    let storeId: Int64?
    let spuId: Int64?
    let skuId: Int64?
    let skuName: String?
    let skuImage: String?
    let price: Decimal?
    let quantity: Int?
    let totalAmount: Decimal?
    let discountAmount: Decimal?
}

/// 服务端按状态机下发的操作按钮（前端只渲染，不自建按钮矩阵）
struct OrderAction: Decodable, Hashable {
    let type: String?
    let text: String?
}

/// 金额明细（OrderDetailVO.amountDetail）
struct OrderAmountDetail: Decodable, Hashable {
    let goodsAmount: Decimal?
    let freightAmount: Decimal?
    let discountAmount: Decimal?
    let payAmount: Decimal?
}

/// 状态流水（只读日志，不做状态推算）
struct OrderTimelineEntry: Decodable, Hashable {
    let fromStatus: Int?
    let toStatus: Int?
    let toStatusText: String?
    let operatorType: Int?
    let operatorId: Int64?
    let reason: String?
    let time: String?
}

/// 子单摘要（拆单结果，按店铺展示）
struct ChildOrder: Decodable, Hashable {
    let orderId: Int64?
    let orderNo: String?
    let storeId: Int64?
    let status: Int?
    let statusText: String?
    let totalAmount: Decimal?
    let discountAmount: Decimal?
    let payAmount: Decimal?
    let itemCount: Int?
}

// MARK: - 列表 / 详情

/// GET /api/v1/orders 列表项（OrderListVO）
struct OrderListItem: Decodable, Identifiable, Hashable {
    let orderId: Int64
    let orderNo: String?
    let status: Int
    let statusText: String?
    let paymentStatus: String?
    let totalAmount: Decimal?
    let discountAmount: Decimal?
    let freightAmount: Decimal?
    let payAmount: Decimal?
    let itemCount: Int?
    let storeCount: Int?
    let items: [OrderItem]?
    /// 支付截止（ISO8601，倒计时唯一依据 —— 裁决 C7，禁止硬编码分钟数）
    let payDeadline: String?
    let createdAt: String?
    let actions: [OrderAction]?

    var id: Int64 { orderId }
    var statusValue: OrderStatus? { OrderStatus(raw: status) }
}

/// GET /api/v1/orders/{orderId} 详情（OrderDetailVO）
struct OrderDetail: Decodable {
    let orderId: Int64
    let orderNo: String?
    let status: Int
    let statusText: String?
    let paymentStatus: String?
    let amountDetail: OrderAmountDetail?
    let items: [OrderItem]?
    let children: [ChildOrder]?
    let receiverName: String?
    let receiverMobile: String?
    let receiverAddress: String?
    let remark: String?
    let payType: Int?
    let payTime: String?
    let payDeadline: String?
    let deliveryTime: String?
    let receiveTime: String?
    let timeline: [OrderTimelineEntry]?
    let actions: [OrderAction]?
    let version: Int64?
    let createdAt: String?

    var statusValue: OrderStatus? { OrderStatus(raw: status) }
    /// 应付金额（明细优先；PayView 直读顶层缺失时兜底 0）
    var payableAmount: Decimal? { amountDetail?.payAmount }
}

/// 取消/确认收货回执（服务端返回 Map，按需取字段）
struct OrderActionResponse: Decodable {
    let orderId: Int64?
    let orderNo: String?
    let status: Int?
    let statusText: String?
}
