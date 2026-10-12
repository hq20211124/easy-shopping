import Foundation

/// 商品卡片模型。
/// 同时用于 `GET /api/v1/products` 列表与 `GET /api/v1/search/products` 搜索结果
/// （两者字段一致，搜索侧字段更少，因此可选字段一律 Optional）。
struct ProductCardItem: Decodable, Identifiable, Hashable {
    let spuId: Int64
    let spuCode: String?
    let name: String?
    let subtitle: String?
    let mainImage: String?
    /// 最低价（价格服务降级时可能为 null）
    let price: Decimal?
    /// 划线价
    let originalPrice: Decimal?
    let salesCount: Int64?
    let skuCount: Int?

    var id: Int64 { spuId }
}

/// 热搜词 `GET /api/v1/search/hot` → `[{keyword, heat}]`
struct HotWord: Decodable, Identifiable, Hashable {
    let keyword: String
    let heat: Int64?

    var id: String { keyword }
}
