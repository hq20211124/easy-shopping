import Foundation

/// 订单列表：状态 Tab + 分页（page/pageSize，服务端 PageResult）。
@MainActor
final class OrderListViewModel: ObservableObject {

    @Published var tab: OrderTab = .all
    @Published var items: [OrderListItem] = []
    @Published var isLoading = false
    @Published var message: String?

    private var page = 1
    private var total = 0
    private let pageSize = 20

    var hasMore: Bool { items.count < total }

    /// 切 Tab / 首次进入
    func reload() async {
        page = 1
        await load(reset: true)
    }

    /// 滚动到底加载下一页
    func loadMore() async {
        guard !isLoading, hasMore else { return }
        page += 1
        await load(reset: false)
    }

    private func load(reset: Bool) async {
        isLoading = true
        defer { isLoading = false }

        var query = [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "pageSize", value: String(pageSize))
        ]
        if let status = tab.queryValue {
            query.append(URLQueryItem(name: "status", value: status))
        }

        do {
            let result: PageResult<OrderListItem> = try await APIClient.shared.request(
                "/api/v1/orders",
                query: query
            )
            total = Int(result.total)
            if reset {
                items = result.list
            } else {
                items.append(contentsOf: result.list)
            }
        } catch {
            // 首页失败给错误态入口；翻页失败静默（保留已加载数据）
            if reset {
                items = []
            }
            message = error.localizedDescription
        }
    }

    // MARK: 操作

    /// 取消订单（仅待付款，服务端状态机校验；失败 msg 直显）
    func cancel(_ item: OrderListItem) async {
        do {
            let _: OrderActionResponse = try await APIClient.shared.request(
                "/api/v1/orders/\(item.orderId)/cancel",
                method: .post,
                body: CancelOrderBody(reason: nil)
            )
            await reload()
        } catch {
            message = error.localizedDescription
        }
    }
}

/// 取消订单请求（reason 可空）
struct CancelOrderBody: Encodable {
    let reason: String?
}

/// 订单详情逻辑
@MainActor
final class OrderDetailViewModel: ObservableObject {

    @Published var detail: OrderDetail?
    @Published var isLoading = false
    @Published var message: String?

    let orderId: Int64

    init(orderId: Int64) {
        self.orderId = orderId
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let response: OrderDetail = try await APIClient.shared.request(
                "/api/v1/orders/\(orderId)"
            )
            detail = response
        } catch {
            message = error.localizedDescription
        }
    }

    /// 取消订单
    func cancel() async {
        do {
            let _: OrderActionResponse = try await APIClient.shared.request(
                "/api/v1/orders/\(orderId)/cancel",
                method: .post,
                body: CancelOrderBody(reason: nil)
            )
            await load()
        } catch {
            message = error.localizedDescription
        }
    }

    /// 确认收货（待收货 → 已完成）
    func confirmReceive() async {
        do {
            let _: OrderActionResponse = try await APIClient.shared.request(
                "/api/v1/orders/\(orderId)/confirm",
                method: .post,
                body: EmptyBody()
            )
            await load()
        } catch {
            message = error.localizedDescription
        }
    }
}
