import SwiftUI

/// 订单列表：状态 Tab（全部/待付款/待发货/待收货/已完成）+ 分页加载。
/// status 参数为单值，售后 Tab（退款中/已退款）P0 不提供合并查询入口。
struct OrderListView: View {
    let initialStatus: Int?

    @EnvironmentObject private var router: AppRouter
    // 单点初始化：与 OrderDetailView / CheckoutView / PayView 写法一致。
    // （旧写法 `@StateObject private var vm = OrderListViewModel()` 再在 init 里
    //  `_vm = StateObject(wrappedValue: {...}())`，属于双重初始化属性包装器 backing store：
    //  每次构造 View 都会白建一个 VM 对象再丢弃。）
    @StateObject private var vm: OrderListViewModel
    @State private var pendingCancel: OrderListItem?

    init(initialStatus: Int?) {
        self.initialStatus = initialStatus
        _vm = StateObject(wrappedValue: {
            let vm = OrderListViewModel()
            vm.tab = OrderTab(rawValue: initialStatus ?? -1) ?? .all
            return vm
        }())
    }

    var body: some View {
        VStack(spacing: 0) {
            tabBar
            Divider()
            listContent
        }
        .background(AppTheme.background)
        .navigationTitle("我的订单")
        .navigationBarTitleDisplayMode(.inline)
        .toast($vm.message)
        .task {
            await vm.reload()
        }
        .confirmationDialog(
            "确定取消该订单？",
            isPresented: Binding(
                get: { pendingCancel != nil },
                set: { if !$0 { pendingCancel = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("取消订单", role: .destructive) {
                if let item = pendingCancel {
                    Task { await vm.cancel(item) }
                }
                pendingCancel = nil
            }
            Button("再想想", role: .cancel) { pendingCancel = nil }
        }
    }

    // MARK: Tab

    private var tabBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 18) {
                ForEach(OrderTab.allCases) { tab in
                    Button {
                        guard vm.tab != tab else { return }
                        vm.tab = tab
                        Task { await vm.reload() }
                    } label: {
                        VStack(spacing: 5) {
                            Text(tab.title)
                                .font(vm.tab == tab ? .subheadline.bold() : .subheadline)
                                .foregroundColor(vm.tab == tab ? AppTheme.primary : .secondary)
                            Rectangle()
                                .fill(vm.tab == tab ? AppTheme.primary : Color.clear)
                                .frame(height: 2)
                        }
                        .fixedSize()
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 8)
        }
        .background(AppTheme.card)
    }

    // MARK: 列表

    @ViewBuilder
    private var listContent: some View {
        if vm.isLoading && vm.items.isEmpty {
            LoadingView()
        } else if vm.items.isEmpty {
            ScrollView {
                EmptyStateView(title: "暂无订单", systemImage: "package")
            }
        } else {
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(vm.items) { item in
                        orderCard(item)
                            .onAppear {
                                if item.id == vm.items.last?.id {
                                    Task { await vm.loadMore() }
                                }
                            }
                    }

                    if vm.isLoading && !vm.items.isEmpty {
                        ProgressView()
                            .padding(.vertical, 14)
                    }
                }
                .padding(12)
            }
            .refreshable {
                await vm.reload()
            }
        }
    }

    // MARK: 订单卡

    private func orderCard(_ item: OrderListItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text("订单 \(item.orderNo ?? "")")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                Spacer()
                Text(item.statusText ?? item.statusValue?.text ?? "")
                    .font(.caption.bold())
                    .foregroundColor(AppTheme.primary)
            }

            ForEach(item.items ?? [], id: \.self) { goods in
                goodsRow(goods)
            }

            HStack(spacing: 4) {
                Text("共 \(item.itemCount ?? 0) 件")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
                Text("实付 ")
                    .font(.caption)
                    .foregroundColor(.secondary)
                AmountText(amount: item.payAmount, font: .subheadline)
            }

            // 待付款倒计时（读 pay_deadline，不硬编码超时分钟数）
            if item.status == OrderStatus.unpaid.rawValue {
                CountdownText(deadline: item.payDeadline)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            HStack {
                Spacer()
                ForEach(item.actions ?? [], id: \.self) { action in
                    actionButton(action, order: item)
                }
            }
        }
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
        .contentShape(Rectangle())
        .onTapGesture {
            router.push(.orderDetail(orderId: item.orderId))
        }
    }

    private func goodsRow(_ goods: OrderItem) -> some View {
        HStack(spacing: 10) {
            RemoteImage(urlString: goods.skuImage)
                .frame(width: 56, height: 56)
                .cornerRadius(8)
                .clipped()
            VStack(alignment: .leading, spacing: 4) {
                Text(goods.skuName ?? "")
                    .font(.subheadline)
                    .lineLimit(2)
                HStack {
                    AmountText(amount: goods.price, font: .caption, color: .secondary)
                    Spacer()
                    Text("x\(goods.quantity ?? 0)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    /// 服务端 action 只渲染不决策：pay/cancel（0）、logistics/confirm（2）
    @ViewBuilder
    private func actionButton(_ action: OrderAction, order: OrderListItem) -> some View {
        let title = action.text ?? defaultActionTitle(action.type)
        Button {
            handleAction(action.type, order: order)
        } label: {
            Text(title)
                .font(.caption)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(isPrimaryAction(action.type) ? AppTheme.primary : Color.gray.opacity(0.5),
                                lineWidth: 1)
                )
                .foregroundColor(isPrimaryAction(action.type) ? AppTheme.primary : .secondary)
        }
        .buttonStyle(.plain)
    }

    private func isPrimaryAction(_ type: String?) -> Bool {
        type == "pay" || type == "confirm"
    }

    private func defaultActionTitle(_ type: String?) -> String {
        switch type {
        case "pay": return "去付款"
        case "cancel": return "取消订单"
        case "logistics": return "查看物流"
        case "confirm": return "确认收货"
        default: return "查看"
        }
    }

    private func handleAction(_ type: String?, order: OrderListItem) {
        switch type {
        case "pay":
            router.push(.pay(orderId: order.orderId))
        case "cancel":
            pendingCancel = order
        case "logistics":
            vm.message = "物流查询 P1 接入"
        case "confirm":
            Task {
                do {
                    let _: OrderActionResponse = try await APIClient.shared.request(
                        "/api/v1/orders/\(order.orderId)/confirm",
                        method: .post,
                        body: EmptyBody()
                    )
                    await vm.reload()
                } catch {
                    vm.message = error.localizedDescription
                }
            }
        default:
            router.push(.orderDetail(orderId: order.orderId))
        }
    }
}
