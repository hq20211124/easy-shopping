import Foundation

@MainActor
final class ProductDetailViewModel: ObservableObject {

    let spuId: Int64

    @Published var detail: ProductDetail?
    @Published var isLoading = false
    @Published var errorMessage: String?
    /// 已选规格：规格名 → 值
    @Published var selectedSpecs: [String: String] = [:]
    @Published var quantity = 1
    @Published var addingToCart = false
    /// toast 文案（服务端 msg 直显）
    @Published var message: String?

    init(spuId: Int64) {
        self.spuId = spuId
    }

    // MARK: 派生数据

    var specGroups: [SpecGroup] {
        detail?.specs ?? []
    }

    var skus: [SkuItem] {
        detail?.skus ?? []
    }

    /// 主图画廊（主图兜底）
    var galleryImages: [String] {
        var list = detail?.images ?? []
        if list.isEmpty, let main = detail?.mainImage {
            list = [main]
        }
        return list
    }

    /// 当前选中的 SKU（需选满所有规格维度且在架）
    var selectedSku: SkuItem? {
        let groups = specGroups
        if groups.isEmpty {
            return skus.first(where: { $0.onShelf }) ?? skus.first
        }
        guard selectedSpecs.count == groups.count else { return nil }
        return skus.first { sku in
            guard sku.onShelf else { return false }
            return groups.allSatisfy { selectedSpecs[$0.name] == sku.specs?[$0.name] }
        }
    }

    /// 详情页价格：优先 SKU 现价，其次 SPU 兜底价
    var displayPrice: Decimal? {
        selectedSku?.price ?? detail?.price
    }

    var displayOriginalPrice: Decimal? {
        detail?.originalPrice
    }

    /// 已选文案（按规格组顺序拼接）
    var selectedText: String {
        specGroups.compactMap { selectedSpecs[$0.name] }.joined(separator: " / ")
    }

    var canBuy: Bool {
        selectedSku != nil || skus.isEmpty
    }

    // MARK: 加载

    func load() async {
        guard detail == nil else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let response: ProductDetail = try await APIClient.shared.request("/api/v1/products/\(spuId)")
            detail = response
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: 规格选择

    func toggle(group: String, value: String) {
        if selectedSpecs[group] == value {
            selectedSpecs[group] = nil
        } else {
            selectedSpecs[group] = value
        }
    }

    /// 该选项在当前其它维度选择下是否存在可售 SKU → 不存在则禁用
    func isOptionDisabled(group: String, value: String) -> Bool {
        let exists = skus.contains { sku in
            guard sku.onShelf, sku.specs?[group] == value else { return false }
            return selectedSpecs.allSatisfy { key, val in
                key == group || sku.specs?[key] == val
            }
        }
        return !exists
    }

    // MARK: 动作

    @discardableResult
    func addToCart() async -> Bool {
        guard AppState.shared.requireLogin() else { return false }
        guard let sku = selectedSku else {
            message = specGroups.isEmpty ? "商品暂无可售规格" : "请先选择规格"
            return false
        }
        addingToCart = true
        defer { addingToCart = false }

        struct AddCartBody: Encodable {
            let skuId: Int64
            let quantity: Int
        }
        struct AddCartResult: Decodable {
            let cartItemId: Int64?
            let cartTotalQuantity: Int?
        }

        do {
            let _: AddCartResult = try await APIClient.shared.request(
                "/api/v1/cart",
                method: .post,
                body: AddCartBody(skuId: sku.skuId, quantity: quantity)
            )
            await AppState.shared.refreshCartCount()
            message = "已加入购物车"
            return true
        } catch {
            // 库存不足（3002）/ 上架状态变化（3003）等错误文案直接展示服务端 msg
            message = error.localizedDescription
            return false
        }
    }

    /// 立即购买 → 结算参数（仅 ID + 展示快照，提交以服务端计价为准）
    func buyNowItem() -> CheckoutItem? {
        guard let sku = selectedSku else {
            message = specGroups.isEmpty ? "商品暂无可售规格" : "请先选择规格"
            return nil
        }
        let title = detail?.name ?? ""
        let image = sku.images?.first ?? detail?.mainImage ?? ""
        let price = sku.price ?? detail?.price
        return CheckoutItem(skuId: sku.skuId,
                            quantity: quantity,
                            title: title,
                            image: image,
                            price: price,
                            fromCart: false)
    }
}
