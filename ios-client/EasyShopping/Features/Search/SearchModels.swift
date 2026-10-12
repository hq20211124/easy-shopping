import Foundation

/// `GET /api/v1/search/products` 响应（cursor 分页，末页 nextCursor 为 null）
struct SearchResponse: Decodable {
    let query: String?
    let total: Int64?
    let nextCursor: String?
    let list: [ProductCardItem]?
}

/// `GET /api/v1/search/suggestions` 响应
struct SuggestionResponse: Decodable {
    let query: String?
    let suggestions: [SuggestionItem]?
}

/// 建议条目：type = keyword（热搜词）/ product（商品标题，附 productId）
struct SuggestionItem: Decodable, Identifiable, Hashable {
    let text: String?
    let type: String?
    let productId: Int64?

    var id: String { "\(type ?? "")|\(text ?? "")" }
    var isProduct: Bool { type == "product" }
}
