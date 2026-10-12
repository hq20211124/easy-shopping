import SwiftUI

/// 购物车：失效行分区展示 + 勾选/改数量/删除 + 底部结算栏。
/// 行操作以 cartItemId 定位（禁止用 skuId），操作后整体刷新回显服务端结果。
struct CartView: View {
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var appState: AppState
    @StateObject private var vm = CartViewModel()

    var body: some View {
        Group {
            if !appState.isLoggedIn {
                // 游客：前置登录（plans/ios-frontend 2.1 关键动作前置登录）
                EmptyStateView(title: "登录后查看购物车",
                               systemImage: "cart",
                               actionTitle: "去登录") {
                    appState.loginRequired = true
                }
            } else if vm.isLoading && vm.items.isEmpty {
                LoadingView()
            } else if let error = vm.errorMessage, vm.items.isEmpty {
                ErrorStateView(message: error) {
                    Task { await vm.load() }
                }
            } else if vm.items.isEmpty {
                EmptyStateView(title: "购物车是空的",
                               systemImage: "cart",
                               actionTitle: "去逛逛") {
                    router.selectedTab = .home
                }
            } else {
                content
            }
        }
        .background(AppTheme.background)
        .navigationTitle("购物车")
        .navigationBarTitleDisplayMode(.inline)
        .toast($vm.message)
        .task {
            if appState.isLoggedIn { await vm.load() }
        }
    }

    // MARK: 列表

    private var content: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(vm.validItems) { item in
                        CartRow(item: item, vm: vm)
                    }

                    if !vm.invalidItems.isEmpty {
                        invalidSection
                    }
                }
                .padding(12)
            }
            .refreshable {
                await vm.load()
            }

            bottomBar
        }
    }

    /// 失效商品区（服务端仍返回但不计金额，不可勾选）
    private var invalidSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("失效商品（\(vm.invalidItems.count)）")
                .font(.subheadline)
                .foregroundColor(.secondary)

            ForEach(vm.invalidItems) { item in
                CartRow(item: item, vm: vm)
            }

            HStack {
                Spacer()
                Button("清空失效商品") {
                    Task {
                        for item in vm.invalidItems {
                            await vm.remove(item)
                        }
                    }
                }
                .font(.subheadline)
                .foregroundColor(.secondary)
            }
        }
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 底部结算栏

    private var bottomBar: some View {
        HStack(spacing: 12) {
            Button {
                Task { await vm.toggleAll(selected: !vm.isAllSelected) }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: vm.isAllSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(vm.isAllSelected ? AppTheme.primary : .secondary)
                    Text("全选")
                        .font(.subheadline)
                        .foregroundColor(.primary)
                }
            }
            .buttonStyle(.plain)

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                HStack(spacing: 4) {
                    Text("合计:")
                        .font(.subheadline)
                    AmountText(amount: vm.payableAmount, font: .headline)
                }
                Text("已选 \(vm.selectedQuantity) 件")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Button {
                let items = vm.checkoutItems()
                guard !items.isEmpty else {
                    vm.message = "请选择商品"
                    return
                }
                router.push(.checkout(items: items))
            } label: {
                Text("结算(\(vm.selectedQuantity))")
                    .font(.subheadline.bold())
                    .padding(.horizontal, 22)
                    .padding(.vertical, 10)
                    .background(vm.selectedQuantity > 0 ? AppTheme.primary : Color.gray.opacity(0.4))
                    .foregroundColor(.white)
                    .cornerRadius(18)
            }
            .buttonStyle(.plain)
            .disabled(vm.selectedQuantity == 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(AppTheme.card)
        .shadow(color: .black.opacity(0.06), radius: 4, y: -2)
    }
}

// MARK: - 购物车行

private struct CartRow: View {
    let item: CartItem
    @ObservedObject var vm: CartViewModel

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            // 勾选（失效行禁用）
            Button {
                Task { await vm.setSelected(item, selected: !item.isSelected) }
            } label: {
                Image(systemName: item.isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(item.isSelected ? AppTheme.primary : .secondary)
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .disabled(item.isInvalid)

            RemoteImage(urlString: item.mainImage)
                .frame(width: 76, height: 76)
                .cornerRadius(8)
                .clipped()
                .opacity(item.isInvalid ? 0.5 : 1)

            VStack(alignment: .leading, spacing: 6) {
                Text(item.title ?? "")
                    .font(.subheadline)
                    .lineLimit(2)
                    .opacity(item.isInvalid ? 0.5 : 1)

                if let tags = item.discountTags, !tags.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(tags, id: \.self) { tag in
                            Text(tag)
                                .font(.caption2)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 3)
                                        .stroke(AppTheme.primary, lineWidth: 1)
                                )
                                .foregroundColor(AppTheme.primary)
                        }
                    }
                }

                HStack {
                    AmountText(amount: item.price, font: .subheadline)
                    Spacer()
                    if item.isInvalid {
                        Text("已失效")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                HStack {
                    Spacer()
                    if item.isInvalid {
                        Button("删除") {
                            Task { await vm.remove(item) }
                        }
                        .font(.caption)
                        .foregroundColor(.secondary)
                    } else {
                        QuantityStepper(
                            quantity: Binding(
                                get: { item.quantity ?? 1 },
                                set: { newValue in
                                    Task { await vm.setQuantity(item, quantity: newValue) }
                                }
                            ),
                            range: 1...max(item.stock ?? 99, 1)
                        )
                    }
                }
            }
        }
        .padding(10)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }
}
