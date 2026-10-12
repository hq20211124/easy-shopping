import SwiftUI

/// 订单详情：状态 + 倒计时（待付款）+ 收货信息 + 商品 + 金额明细 + 时间线 + 服务端动作按钮。
struct OrderDetailView: View {
    let orderId: Int64

    @EnvironmentObject private var router: AppRouter
    @StateObject private var vm: OrderDetailViewModel
    @State private var showCancelConfirm = false
    @State private var showConfirmReceive = false

    init(orderId: Int64) {
        self.orderId = orderId
        _vm = StateObject(wrappedValue: OrderDetailViewModel(orderId: orderId))
    }

    var body: some View {
        Group {
            if let detail = vm.detail {
                content(detail)
            } else if vm.isLoading {
                LoadingView()
            } else {
                ErrorStateView(message: vm.message ?? "订单加载失败") {
                    Task { await vm.load() }
                }
            }
        }
        .background(AppTheme.background)
        .navigationTitle("订单详情")
        .navigationBarTitleDisplayMode(.inline)
        .toast($vm.message)
        .task {
            await vm.load()
        }
        .confirmationDialog("确定取消该订单？", isPresented: $showCancelConfirm, titleVisibility: .visible) {
            Button("取消订单", role: .destructive) {
                Task { await vm.cancel() }
            }
            Button("再想想", role: .cancel) {}
        }
        .confirmationDialog("确认已收到货？", isPresented: $showConfirmReceive, titleVisibility: .visible) {
            Button("确认收货") {
                Task { await vm.confirmReceive() }
            }
            Button("取消", role: .cancel) {}
        }
    }

    // MARK: 主体

    private func content(_ detail: OrderDetail) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                statusHeader(detail)
                if detail.status == OrderStatus.unpaid.rawValue,
                   let deadline = detail.payDeadline {
                    countdownCard(deadline)
                }
                receiverCard(detail)
                itemsCard(detail)
                amountCard(detail)
                if let children = detail.children, !children.isEmpty {
                    childOrdersCard(children)
                }
                infoCard(detail)
                if let timeline = detail.timeline, !timeline.isEmpty {
                    timelineCard(timeline)
                }
            }
            .padding(12)
            .padding(.bottom, 8)
        }
        .safeAreaInset(edge: .bottom) {
            actionBar(detail)
        }
    }

    // MARK: 状态头

    private func statusHeader(_ detail: OrderDetail) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(detail.statusText ?? detail.statusValue?.text ?? "")
                    .font(.title3.bold())
                    .foregroundColor(AppTheme.primary)
                if let orderNo = detail.orderNo {
                    Text("订单号 \(orderNo)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            Spacer()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    private func countdownCard(_ deadline: String) -> some View {
        HStack {
            Image(systemName: "clock")
                .foregroundColor(AppTheme.primary)
            CountdownText(deadline: deadline, prefix: "剩余支付时间 ")
            Spacer()
        }
        .font(.subheadline)
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 收货信息

    private func receiverCard(_ detail: OrderDetail) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "location.fill")
                .foregroundColor(AppTheme.primary)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 10) {
                    Text(detail.receiverName ?? "")
                        .font(.subheadline.bold())
                    Text(detail.receiverMobile ?? "")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Text(detail.receiverAddress ?? "")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 商品

    private func itemsCard(_ detail: OrderDetail) -> some View {
        VStack(spacing: 10) {
            ForEach(detail.items ?? [], id: \.self) { goods in
                HStack(spacing: 10) {
                    RemoteImage(urlString: goods.skuImage)
                        .frame(width: 60, height: 60)
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
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 金额明细

    private func amountCard(_ detail: OrderDetail) -> some View {
        let amount = detail.amountDetail
        return VStack(spacing: 8) {
            row("商品金额", Money.text(amount?.goodsAmount))
            row("运费", Money.text(amount?.freightAmount))
            row("优惠", "-" + Money.text(amount?.discountAmount))
            Divider()
            HStack {
                Text("实付款")
                    .font(.subheadline.bold())
                Spacer()
                AmountText(amount: amount?.payAmount, font: .headline)
            }
        }
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 子单

    private func childOrdersCard(_ children: [ChildOrder]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("子订单")
                .font(.subheadline.bold())
            ForEach(children, id: \.self) { child in
                HStack {
                    Text("店铺 \(child.storeId.map { "#\($0)" } ?? "")")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    Text(child.statusText ?? OrderStatus(raw: child.status)?.text ?? "")
                        .font(.caption)
                        .foregroundColor(AppTheme.primary)
                    AmountText(amount: child.payAmount, font: .caption)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 信息（备注/创建/支付/收货时间）

    private func infoCard(_ detail: OrderDetail) -> some View {
        VStack(spacing: 8) {
            if let remark = detail.remark, !remark.isEmpty {
                row("备注", remark)
            }
            row("创建时间", ISODate.display(detail.createdAt))
            if let payTime = detail.payTime {
                row("支付时间", ISODate.display(payTime))
            }
            if let delivery = detail.deliveryTime {
                row("发货时间", ISODate.display(delivery))
            }
            if let receive = detail.receiveTime {
                row("收货时间", ISODate.display(receive))
            }
        }
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 时间线

    private func timelineCard(_ timeline: [OrderTimelineEntry]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("订单动态")
                .font(.subheadline.bold())
            ForEach(Array(timeline.enumerated()), id: \.offset) { _, entry in
                HStack(alignment: .top, spacing: 8) {
                    Circle()
                        .fill(AppTheme.primary)
                        .frame(width: 6, height: 6)
                        .padding(.top, 5)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.toStatusText ?? OrderStatus(raw: entry.toStatus)?.text ?? "")
                            .font(.caption.bold())
                        if let reason = entry.reason, !reason.isEmpty {
                            Text(reason)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                        Text(ISODate.display(entry.time))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 底部按钮（服务端 actions 驱动）

    @ViewBuilder
    private func actionBar(_ detail: OrderDetail) -> some View {
        let actions = detail.actions ?? []
        if !actions.isEmpty {
            HStack(spacing: 10) {
                Spacer()
                ForEach(actions, id: \.self) { action in
                    Button {
                        handle(action)
                    } label: {
                        Text(action.text ?? action.type ?? "")
                            .font(.subheadline)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 9)
                            .overlay(
                                RoundedRectangle(cornerRadius: 17)
                                    .stroke(isPrimary(action.type) ? AppTheme.primary : Color.gray.opacity(0.5),
                                            lineWidth: 1)
                            )
                            .foregroundColor(isPrimary(action.type) ? AppTheme.primary : .secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
        }
    }

    private func isPrimary(_ type: String?) -> Bool {
        type == "pay" || type == "confirm"
    }

    private func handle(_ action: OrderAction) {
        switch action.type {
        case "pay":
            router.push(.pay(orderId: orderId))
        case "cancel":
            showCancelConfirm = true
        case "confirm":
            showConfirmReceive = true
        case "logistics":
            vm.message = "物流查询 P1 接入"
        default:
            vm.message = "该操作 P0 未接入"
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(title)
                .font(.subheadline)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.subheadline)
                .multilineTextAlignment(.trailing)
        }
    }
}
