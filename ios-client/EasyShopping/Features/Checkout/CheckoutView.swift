import SwiftUI

/// 结算页：地址卡 → 商品清单 → 优惠券（试算降级） → 备注 → 底部提交。
/// 金额本地计算（P0 无 /orders/preview），提交以服务端计价为准。
struct CheckoutView: View {
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var appState: AppState
    @StateObject private var vm: CheckoutViewModel
    @State private var showCouponSheet = false

    let items: [CheckoutItem]

    init(items: [CheckoutItem]) {
        self.items = items
        _vm = StateObject(wrappedValue: {
            let vm = CheckoutViewModel()
            vm.items = items
            return vm
        }())
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                addressCard
                itemsCard
                couponCard
                remarkCard
                amountCard
            }
            .padding(12)
        }
        .background(AppTheme.background)
        .navigationTitle("确认订单")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) { submitBar }
        .task {
            await vm.loadIfNeeded()
        }
        .onAppear {
            // 从地址列表选择模式返回后回填
            vm.syncSelectedAddress()
        }
        .sheet(isPresented: $showCouponSheet) {
            couponSheet
        }
        .toast($vm.message)
        // P0-4 ③：选了券但券没生效时，阻断下单并要求用户明确确认（禁止静默按原价成交）
        .confirmationDialog(
            vm.pendingConfirm?.title ?? "",
            isPresented: Binding(
                get: { vm.pendingConfirm != nil },
                set: { if !$0 { vm.cancelSubmit() } }
            ),
            titleVisibility: .visible
        ) {
            Button(vm.pendingConfirm?.confirmTitle ?? "仍按原价支付") {
                Task { await vm.confirmSubmitWithoutCoupon(router: router) }
            }
            Button(vm.pendingConfirm?.cancelTitle ?? "返回选择优惠券", role: .cancel) {
                vm.cancelSubmit()
            }
        } message: {
            Text(vm.pendingConfirm?.message ?? "")
        }
    }

    // MARK: 地址

    private var addressCard: some View {
        Button {
            router.push(.addressList(selecting: true))
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "location.fill")
                    .foregroundColor(AppTheme.primary)
                if let address = vm.selectedAddress {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 10) {
                            Text(address.name)
                                .font(.subheadline.bold())
                            Text(address.mobile)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Spacer()
                        }
                        Text(address.fullAddress)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } else {
                    Text(vm.isLoading ? "加载地址…" : "添加收货地址")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(12)
            .background(AppTheme.card)
            .cornerRadius(AppTheme.cornerRadius)
            // P0-4：地址加载失败原因必须可见（VM 已写入 errorMessage，此前从未渲染）
            .overlay(alignment: .bottomLeading) {
                if let error = vm.errorMessage, !vm.isLoading {
                    Text(error)
                        .font(.caption2)
                        .foregroundColor(.red)
                        .padding(.leading, 12)
                        .padding(.bottom, 2)
                }
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: 商品

    private var itemsCard: some View {
        VStack(spacing: 10) {
            ForEach(vm.items, id: \.self) { item in
                HStack(spacing: 10) {
                    RemoteImage(urlString: item.image)
                        .frame(width: 64, height: 64)
                        .cornerRadius(8)
                        .clipped()
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.title)
                            .font(.subheadline)
                            .lineLimit(2)
                        HStack {
                            AmountText(amount: item.price, font: .subheadline)
                            Spacer()
                            Text("x\(item.quantity)")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 优惠券

    /// 券入口：加载失败 / 已选券未生效 / 试算失败都要**可见**，不能只剩"无可用"三个字
    private var couponCard: some View {
        Button {
            showCouponSheet = true
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("优惠券")
                        .font(.subheadline)
                    Spacer()
                    couponSummaryText
                        .font(.subheadline)
                        .foregroundColor(couponSummaryColor)
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                if let error = vm.couponLoadError {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.red)
                } else if let error = vm.couponPreviewError, vm.selectedCouponId != nil {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.orange)
                } else if let notice = vm.couponProblemNotice {
                    Text(notice.message)
                        .font(.caption)
                        .foregroundColor(.orange)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppTheme.card)
            .cornerRadius(AppTheme.cornerRadius)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var couponSummaryText: some View {
        if vm.previewing {
            Text("试算中…")
                .foregroundColor(.secondary)
        } else if let preview = vm.preview, preview.usable,
                  let discount = preview.discount, discount > 0 {
            Text("-\(Money.text(discount))")
                .foregroundColor(AppTheme.primary)
        } else if vm.couponLoadError != nil {
            Text("加载失败")
                .foregroundColor(.red)
        } else if vm.hasCouponProblem {
            Text("未生效")
                .foregroundColor(.orange)
        } else if !vm.coupons.isEmpty {
            Text("\(vm.coupons.count)张可用")
                .foregroundColor(.secondary)
        } else {
            Text("无可用")
                .foregroundColor(.secondary)
        }
    }

    private var couponSummaryColor: Color {
        if vm.couponLoadError != nil { return .red }
        if vm.hasCouponProblem { return .orange }
        return .secondary
    }

    private var couponSheet: some View {
        NavigationStack {
            List {
                if let error = vm.couponLoadError {
                    // P0-4：券加载失败可见 + 可重试（不再静默显示"暂无可用"）
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(error)
                                .font(.footnote)
                                .foregroundColor(.red)
                            Button("重新加载优惠券") {
                                Task { await vm.reloadCoupons() }
                            }
                            .font(.footnote)
                        }
                    }
                } else {
                    Section {
                        Button("不使用优惠券") {
                            vm.selectCoupon(nil)
                            showCouponSheet = false
                        }
                        .foregroundColor(AppTheme.primary)

                        if vm.coupons.isEmpty {
                            Text("暂无可用优惠券")
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }

                        ForEach(vm.coupons) { coupon in
                            Button {
                                vm.selectCoupon(coupon.couponId)
                                showCouponSheet = false
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(coupon.displayName)
                                            .font(.subheadline)
                                            // 展示态统一走 CouponStatus 单点映射（后端下发英文串）
                                            .foregroundColor(coupon.statusValue.isUsable ? .primary : .secondary)
                                        Text(coupon.statusValue.text)
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    if vm.selectedCouponId == coupon.couponId {
                                        Image(systemName: "checkmark")
                                            .foregroundColor(AppTheme.primary)
                                    }
                                }
                            }
                        }
                    } header: {
                        Text("选择优惠券")
                    }
                }

                if let notice = vm.couponProblemNotice {
                    Section {
                        Text(notice.message)
                            .font(.footnote)
                            .foregroundColor(.orange)
                    }
                }
            }
            .navigationTitle("优惠券")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { showCouponSheet = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: 备注

    private var remarkCard: some View {
        HStack {
            Text("备注")
                .font(.subheadline)
            TextField("选填，给商家留言", text: $vm.remark)
                .font(.subheadline)
        }
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 金额明细

    private var amountCard: some View {
        VStack(spacing: 8) {
            amountRow("商品合计", Money.text(vm.subtotal))
            amountRow("优惠", "-" + Money.text(vm.discount))
            // 券未生效时给出**常驻**可见原因（服务端 reason / 试算异常 / 试算未完成都在文案里）
            if let notice = vm.couponProblemNotice {
                Text(notice.message)
                    .font(.caption)
                    .foregroundColor(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            amountRow("运费", "以服务端计价为准")
        }
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    private func amountRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .font(.subheadline)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.subheadline)
        }
    }

    // MARK: 提交栏

    private var submitBar: some View {
        HStack(spacing: 12) {
            VStack(alignment: .trailing, spacing: 2) {
                HStack(spacing: 4) {
                    Text("应付:")
                        .font(.subheadline)
                    AmountText(amount: vm.payable, font: .title3.bold())
                }
                Text("共 \(vm.itemCount) 件")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Button {
                Task { await vm.submit(router: router) }
            } label: {
                Group {
                    if vm.submitting {
                        ProgressView().tint(.white)
                    } else {
                        Text("提交订单")
                    }
                }
                .font(.subheadline.bold())
                .padding(.horizontal, 30)
                .padding(.vertical, 12)
                .background(vm.canSubmit ? AppTheme.primary : Color.gray.opacity(0.4))
                .foregroundColor(.white)
                .cornerRadius(20)
            }
            .disabled(!vm.canSubmit)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }
}
