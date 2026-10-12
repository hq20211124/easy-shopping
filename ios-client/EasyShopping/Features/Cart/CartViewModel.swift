import Foundation

@MainActor
final class CartViewModel: ObservableObject {

    @Published var items: [CartItem] = []
    @Published var totalQuantity = 0
    @Published var selectedQuantity = 0
    @Published var maxItems = 200
    @Published var totalAmount: Decimal = 0
    @Published var payableAmount: Decimal = 0
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var message: String?

    var validItems: [CartItem] {
        items.filter { !$0.isInvalid }
    }

    var invalidItems: [CartItem] {
        items.filter { $0.isInvalid }
    }

    var selectedItems: [CartItem] {
        validItems.filter { $0.isSelected }
    }

    var isAllSelected: Bool {
        !validItems.isEmpty && validItems.allSatisfy { $0.isSelected }
    }

    // MARK: 加载

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let response: CartList = try await APIClient.shared.request("/api/v1/cart")
            apply(response)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func apply(_ response: CartList) {
        items = response.items ?? []
        totalQuantity = response.totalQuantity ?? 0
        selectedQuantity = response.selectedQuantity ?? 0
        maxItems = response.maxItems ?? 200
        totalAmount = response.totalAmount ?? 0
        payableAmount = response.payableAmount ?? 0
        AppState.shared.cartCount = totalQuantity
    }

    // MARK: 操作（写接口全部带 Idempotency-Key，结果以重新拉取的列表为准）

    func setSelected(_ item: CartItem, selected: Bool) async {
        do {
            let _: CartActionResult = try await APIClient.shared.request(
                "/api/v1/cart/\(item.cartItemId)",
                method: .put,
                body: CartSelectedBody(selected: selected)
            )
            await load()
        } catch {
            message = error.localizedDescription
        }
    }

    func setQuantity(_ item: CartItem, quantity: Int) async {
        guard quantity >= 1, quantity != item.quantity else { return }
        do {
            let _: CartActionResult = try await APIClient.shared.request(
                "/api/v1/cart/\(item.cartItemId)",
                method: .put,
                body: CartQuantityBody(quantity: quantity)
            )
            await load()
        } catch {
            // 数量超上限等错误直显服务端 msg，并刷新以回显真实数量
            message = error.localizedDescription
            await load()
        }
    }

    func toggleAll(selected: Bool) async {
        do {
            let _: CartActionResult = try await APIClient.shared.request(
                "/api/v1/cart/select",
                method: .post,
                body: CartSelectBody(cartItemIds: [], selected: selected)
            )
            await load()
        } catch {
            message = error.localizedDescription
        }
    }

    func remove(_ item: CartItem) async {
        do {
            let _: CartActionResult = try await APIClient.shared.request(
                "/api/v1/cart/\(item.cartItemId)",
                method: .delete
            )
            await load()
        } catch {
            message = error.localizedDescription
        }
    }

    // MARK: 结算

    /// 勾选行 → 结算参数（仅 ID + 展示快照；提交以服务端计价为准）
    func checkoutItems() -> [CheckoutItem] {
        selectedItems
            .compactMap { item -> CheckoutItem? in
                guard let skuId = item.skuId else { return nil }
                return CheckoutItem(skuId: skuId,
                                    quantity: item.quantity ?? 1,
                                    title: item.title ?? "",
                                    image: item.mainImage ?? "",
                                    price: item.price,
                                    fromCart: true)
            }
    }
}
