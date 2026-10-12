//
//  CheckoutViewModel.swift
//  EasyShopping
//
//  结算逻辑：地址加载/选择、优惠券列表与试算（降级容错）、提交下单。
//

import Foundation

@MainActor
final class CheckoutViewModel: ObservableObject {

    @Published var items: [CheckoutItem] = []
    @Published var addresses: [Address] = []
    @Published var selectedAddress: Address?
    @Published var coupons: [CheckoutCoupon] = []
    @Published var selectedCouponId: Int64?
    @Published var preview: CouponPreviewResponse?
    @Published var remark: String = ""
    @Published var isLoading = false
    @Published var submitting = false
    @Published var errorMessage: String?
    @Published var message: String?

    private var didLoad = false

    // MARK: - 派生金额（本地计算，P0 无订单预览接口 /orders/preview）

    /// 商品合计
    var subtotal: Decimal {
        items.reduce(Decimal.zero) { sum, item in
            guard let price = item.price else { return sum }
            return sum + price * Decimal(item.quantity)
        }
    }

    /// 优惠金额（仅当试算通过）
    var discount: Decimal {
        guard let preview, preview.usable else { return Decimal.zero }
        return preview.discount ?? Decimal.zero
    }

    /// 应付金额
    var payable: Decimal {
        max(subtotal - discount, 0)
    }

    var itemCount: Int {
        items.reduce(0) { $0 + $1.quantity }
    }

    var canSubmit: Bool {
        !items.isEmpty && selectedAddress != nil && !submitting
    }

    // MARK: - 加载

    func loadIfNeeded(force: Bool = false) async {
        if didLoad && !force { return }
        didLoad = true
        isLoading = true
        defer { isLoading = false }

        await loadAddresses()
        await loadCoupons()
        await previewCoupon()
    }

    /// 从地址选择页返回后同步选中地址
    func syncSelectedAddress() {
        let picked = AppState.shared.selectedAddress
        guard let picked, picked.id != selectedAddress?.id else { return }
        selectedAddress = picked
        Task { await previewCoupon() }
    }

    private func loadAddresses() async {
        struct Wrapper: Decodable { let addresses: [Address] }
        do {
            let response: Wrapper = try await APIClient.shared.request("/api/v1/user/addresses")
            addresses = response.addresses
            if selectedAddress == nil {
                // 优先默认地址，其次第一个
                selectedAddress = response.addresses.first(where: { $0.isDefault })
                    ?? response.addresses.first
            }
        } catch {
            // 无地址不阻断页面（提交时再拦截）
            errorMessage = error.localizedDescription
        }
    }

    private func loadCoupons() async {
        do {
            let list: [CheckoutCoupon] = try await APIClient.shared.request(
                "/api/v1/coupons/my",
                query: [URLQueryItem(name: "status", value: "0")]
            )
            coupons = list
        } catch {
            // 优惠券加载失败不阻断下单（降级）
            coupons = []
        }
    }

    // MARK: - 优惠券

    func selectCoupon(_ couponId: Int64?) {
        selectedCouponId = couponId
        Task { await previewCoupon() }
    }

    /// 优惠券试算（失败/不适用均降级为不抵扣，不阻断下单）
    func previewCoupon() async {
        guard let couponId = selectedCouponId, subtotal > 0 else {
            preview = nil
            return
        }
        do {
            let body = CouponPreviewRequest(couponId: couponId, amount: subtotal)
            let response: CouponPreviewResponse = try await APIClient.shared.request(
                "/api/v1/coupons/preview",
                method: .post,
                body: body
            )
            preview = response
        } catch {
            preview = nil
        }
    }

    // MARK: - 提交订单

    func submit(router: AppRouter) async {
        guard let address = selectedAddress else {
            message = "请选择收货地址"
            return
        }
        guard !items.isEmpty else {
            message = "没有可结算的商品"
            return
        }
        guard !submitting else { return }

        submitting = true
        defer { submitting = false }

        // 仅在试算通过时携带 couponId（促销降级时试算不抵扣，不带券提交）
        let couponId = (preview?.usable ?? false) ? selectedCouponId : nil

        let request = CreateOrderRequest(
            items: items.map {
                CreateOrderRequest.CreateOrderItem(skuId: $0.skuId, quantity: $0.quantity)
            },
            addressId: address.id,
            receiverName: address.name,
            receiverMobile: address.mobile,
            receiverAddress: address.fullAddress,
            couponId: couponId,
            remark: remark.isEmpty ? nil : remark,
            source: 1
        )

        do {
            let order: CreateOrderResponse = try await APIClient.shared.request(
                "/api/v1/orders",
                method: .post,
                body: request
            )
            // 购物车结算成功后刷新角标（服务端删除已购购物车行）
            await AppState.shared.refreshCartCount()
            router.replaceTop(with: .pay(orderId: order.orderId))
        } catch {
            // 3002（商品状态变更）等服务端 msg 直接展示
            message = error.localizedDescription
        }
    }
}
