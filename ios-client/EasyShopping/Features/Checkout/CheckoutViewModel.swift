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

    // MARK: 券相关的**可见**故障态（P0-4：禁止静默降级）

    /// 券列表加载失败原因（nil = 正常）。有值时结算页券入口显示红色提示 + 可重试。
    @Published var couponLoadError: String?
    /// 试算失败原因（请求异常，而非服务端判定的"券不满足条件"）。
    /// 有值且用户已选券时，下单前必须弹确认，见 `pendingConfirm`。
    @Published var couponPreviewError: String?
    /// 试算是否正在进行（用于券卡 loading 态，避免用户误以为"选了就生效了"）
    @Published var previewing = false
    /// 选了券但试算未通过 → 下单前弹确认（非 nil 时结算页展示确认弹窗）
    @Published var pendingConfirm: CouponFallbackNotice?

    private var didLoad = false
    /// 试算请求序号：丢弃过期响应，避免快速切券时"后发先到"把结果写错
    private var previewSeq = 0

    // MARK: - 派生金额（本地计算，P0 无订单预览接口 /orders/preview）

    /// 商品合计
    var subtotal: Decimal {
        items.reduce(Decimal.zero) { sum, item in
            guard let price = item.price else { return sum }
            return sum + price * Decimal(item.quantity)
        }
    }

    /// 试算入参：逐行商品金额（后端 `PreviewRequest.amount` 是数组，
    /// `CouponService#preview` 在服务端求和）。口径对齐 web-buyer 的 `lineTotal` 逐行口径。
    private var previewAmounts: [Decimal] {
        items.compactMap { item -> Decimal? in
            guard let price = item.price else { return nil }
            let amount = price * Decimal(item.quantity)
            return amount > 0 ? amount : nil
        }
    }

    /// 优惠金额（仅当服务端试算 eligible=true）
    var discount: Decimal {
        guard let preview = preview, preview.usable else { return Decimal.zero }
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

    /// 下单实际携带的 couponId：**只有服务端试算 eligible=true 才带券**（裁决：宁可不带，
    /// 也不能带一张服务端会拒绝/算不出优惠的券）。
    private var couponIdForOrder: Int64? {
        guard let selectedCouponId = selectedCouponId, preview?.usable == true else { return nil }
        return selectedCouponId
    }

    /// 结算页是否处于"选了券但券没生效"的异常展示态（用于常驻提示条）
    var hasCouponProblem: Bool {
        selectedCouponId != nil && preview?.usable != true
    }

    /// 「选了券但券没生效」的可读说明；随时可读（**不依赖弹窗是否正开着**），
    /// 供券卡 / 金额明细 / 券弹窗做常驻提示。没有选券或券已生效时为 nil。
    var couponProblemNotice: CouponFallbackNotice? { couponFallbackNotice }

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
            couponLoadError = nil
            // 列表可能已不含之前选中的那张（如被核销/过期），清掉避免后续拿着废券试算
            if let selected = selectedCouponId, !list.contains(where: { $0.couponId == selected }) {
                selectedCouponId = nil
                preview = nil
                couponPreviewError = nil
            }
        } catch {
            // P0-4：券加载失败**不静默**——记录原因并在结算页券入口展示 + 提供重试。
            // （静默置空会让用户以为"没有券"，而真实原因可能是契约错配或网络故障）
            coupons = []
            couponLoadError = "优惠券加载失败：\(error.localizedDescription)"
        }
    }

    /// 重新拉取券列表（结算页券入口的"重试"按钮）
    func reloadCoupons() async {
        await loadCoupons()
        if couponLoadError == nil {
            message = "优惠券已刷新"
        }
        await previewCoupon()
    }

    // MARK: - 优惠券

    func selectCoupon(_ couponId: Int64?) {
        selectedCouponId = couponId
        Task { await previewCoupon() }
    }

    /// 优惠券试算。
    ///
    /// P0-2 契约：`POST /api/v1/coupons/preview` 的 `amount` 是**数组**
    /// （`PreviewRequest` record 的 `List<@NotNull BigDecimal> amount`），不是标量。
    ///
    /// P0-4：失败**不再静默置 nil**——原因写进 `couponPreviewError`，由结算页展示，
    /// 并在 `submit()` 前触发确认弹窗，禁止"用户以为用券成功、实则按原价成交"。
    func previewCoupon() async {
        previewSeq += 1
        let seq = previewSeq

        guard let couponId = selectedCouponId, !previewAmounts.isEmpty else {
            preview = nil
            couponPreviewError = nil
            previewing = false
            return
        }

        previewing = true
        defer { if seq == previewSeq { previewing = false } }

        do {
            let body = CouponPreviewRequest(couponId: couponId, amount: previewAmounts)
            let response: CouponPreviewResponse = try await APIClient.shared.request(
                "/api/v1/coupons/preview",
                method: .post,
                body: body
            )
            guard seq == previewSeq else { return }   // 已被更新的选择取代，丢弃过期响应
            preview = response
            couponPreviewError = nil
        } catch {
            guard seq == previewSeq else { return }
            preview = nil
            couponPreviewError = "优惠券试算失败：\(error.localizedDescription)"
        }
    }

    // MARK: - 提交订单

    /// 提交下单。
    ///
    /// P0-4 ③：用户**选了券但券没生效**（试算失败 / 服务端 `eligible=false` / 试算未回来）时，
    /// 绝不能静默按原价成交——必须先弹确认（`pendingConfirm`），用户明确选择"按原价支付"后才建单。
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

        if let notice = couponFallbackNotice {
            pendingConfirm = notice
            return
        }
        await performSubmit(address: address, couponId: couponIdForOrder, router: router)
    }

    /// 确认弹窗"仍按原价支付"：明确放弃优惠券下单（不走 `submit()` 的二次拦截）
    func confirmSubmitWithoutCoupon(router: AppRouter) async {
        pendingConfirm = nil
        guard let address = selectedAddress, !submitting else { return }
        message = "已放弃优惠券，本单按原价支付"
        await performSubmit(address: address, couponId: nil, router: router)
    }

    /// 确认弹窗取消：留在结算页
    func cancelSubmit() {
        pendingConfirm = nil
    }

    /// 选了券但试算未通过 → 生成确认文案；没有选券或券已生效时返回 nil（正常下单）
    private var couponFallbackNotice: CouponFallbackNotice? {
        guard let couponId = selectedCouponId else { return nil }
        if preview?.usable == true { return nil }
        let name = coupons.first(where: { $0.couponId == couponId })?.displayName ?? "该优惠券"
        // 优先级：试算请求异常 → 服务端给出的不可用原因 → 试算仍在进行 → 兜底
        let reason: String
        if let error = couponPreviewError {
            reason = error
        } else if let serverReason = preview?.reason, !serverReason.isEmpty {
            reason = serverReason
        } else if previewing {
            reason = "优惠券试算尚未完成"
        } else {
            reason = "优惠券未生效"
        }
        return CouponFallbackNotice(couponName: name, reason: reason)
    }

    private func performSubmit(address: Address, couponId: Int64?, router: AppRouter) async {
        submitting = true
        defer { submitting = false }

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
