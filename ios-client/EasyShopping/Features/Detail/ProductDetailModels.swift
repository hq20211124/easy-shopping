import Foundation

/// 商品详情 `GET /api/v1/products/{spuId}`（core-product README §1 买家端，字段 camelCase）
struct ProductDetail: Decodable {
    let spuId: Int64
    let spuCode: String?
    let name: String?
    let subtitle: String?
    let mainImage: String?
    let images: [String]?
    let video: String?
    /// 富文本图文详情（HTML）
    let detail: String?
    /// SPU 最低价（兜底展示；SKU 现价以 core-price 为准）
    let price: Decimal?
    let originalPrice: Decimal?
    let salesCount: Int64?
    /// 规格组 [{name, values[]}]
    let specs: [SpecGroup]?
    /// SKU 列表
    let skus: [SkuItem]?
}

/// 规格组（颜色 / 容量 …）
struct SpecGroup: Decodable, Identifiable, Hashable {
    let name: String
    let values: [String]?

    var id: String { name }
}

/// SKU（specs 为 `{"颜色":"黑","容量":"256GB"}`）
struct SkuItem: Decodable, Identifiable, Hashable {
    let skuId: Int64
    let skuCode: String?
    let title: String?
    let specs: [String: String]?
    let images: [String]?
    /// 1 = 上架（随 SPU 上下架同步）
    let status: Int?
    /// 现价；价格服务降级时为 null
    let price: Decimal?

    var id: Int64 { skuId }
    var onShelf: Bool { (status ?? 0) == 1 }
}
