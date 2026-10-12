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

    private var couponCard: some View {
        Button {
            showCouponSheet = true
        } label: {
            HStack {
                Text("优惠券")
                    .font(.subheadline)
                Spacer()
                if let preview = vm.preview, preview.usable,
                   let discount = preview.discount, discount > 0 {
                    Text("-\(Money.text(discount))")
                        .font(.subheadline)
                        .foregroundColor(AppTheme.primary)
                } else if !vm.coupons.isEmpty {
                    Text("\(vm.coupons.count)张可用")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                } else {
                    Text("无可用")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(12)
            .background(AppTheme.card)
            .cornerRadius(AppTheme.cornerRadius)
        }
        .buttonStyle(.plain)
    }

    private var couponSheet: some View {
        NavigationStack {
            List {
                Section {
                    Button("不使用优惠券") {
                        vm.selectCoupon(nil)
                        showCouponSheet = false
                    }
                    .foregroundColor(AppTheme.primary)

                    ForEach(vm.coupons) { coupon in
                        Button {
                            vm.selectCoupon(coupon.couponId)
                            showCouponSheet = false
                        } label: {
                            HStack {
                                Text(coupon.name ?? "优惠券")
                                    .font(.subheadline)
                                    .foregroundColor(.primary)
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
            if let preview = vm.preview, !preview.usable, let reason = preview.reason {
                Text(reason)
                    .font(.caption)
                    .foregroundColor(.secondary)
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
