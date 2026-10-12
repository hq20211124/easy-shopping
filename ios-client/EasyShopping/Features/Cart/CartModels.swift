import Foundation

/// `GET /api/v1/cart` 响应（core-cart README §1，含服务端汇总金额）
struct CartList: Decodable {
    let items: [CartItem]?
    /// 车内总件数（含失效行）
    let totalQuantity: Int?
    /// 已勾选且未失效的件数
    let selectedQuantity: Int?
    /// 车容量上限（服务端下发，前端禁止硬编码）
    let maxItems: Int?
    let totalAmount: Decimal?
    let discountAmount: Decimal?
    let payableAmount: Decimal?
}

/// 购物车行（以 cartItemId 定位，禁止用 skuId）
struct CartItem: Decodable, Identifiable, Hashable {
    let cartItemId: Int64
    let productId: Int64?
    let skuId: Int64?
    let title: String?
    let mainImage: String?
    let price: Decimal?
    let quantity: Int?
    let stock: Int?
    let inStock: Bool?
    let selected: Bool?
    /// 商品失效（下架/删除）标记：失效行仍返回但不计金额
    let invalid: Bool?
    let discountTags: [String]?

    var id: Int64 { cartItemId }
    var isInvalid: Bool { invalid ?? false }
    var isSelected: Bool { selected ?? false }
}

/// PUT /api/v1/cart/{cartItemId} 改数量
struct CartQuantityBody: Encodable {
    let quantity: Int
}

/// PUT /api/v1/cart/{cartItemId} 改勾选
struct CartSelectedBody: Encodable {
    let selected: Bool
}

/// POST /api/v1/cart/select（cartItemIds 为空 = 全选/取消全选）
struct CartSelectBody: Encodable {
    let cartItemIds: [Int64]
    let selected: Bool
}

/// 写操作回执（够用即可，UI 以随后的列表刷新为准）
struct CartActionResult: Decodable {
    let cartTotalQuantity: Int?
    let selectedQuantity: Int?
}
