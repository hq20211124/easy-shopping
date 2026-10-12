import SwiftUI

/// 全局路由：只携带 ID / 轻量参数，禁止携带聚合对象
/// （NavigationPath 会持有整份快照，见 plans/ios-frontend 1.4 关联值约束）。
enum Route: Hashable {
    /// 商品详情
    case productDetail(spuId: Int64)
    /// 结算页：仅 SKU + 数量 + 展示快照（提交以服务端计价为准）
    case checkout(items: [CheckoutItem])
    /// 支付页
    case pay(orderId: Int64)
    /// 订单列表（status 为 0 基 8 值状态，nil = 全部）
    case orderList(status: Int?)
    /// 订单详情
    case orderDetail(orderId: Int64)
    /// 地址列表（selecting=true 为结算页选择模式）
    case addressList(selecting: Bool)
    /// 地址编辑（nil = 新增）
    case addressEdit(addressId: Int64?)
}

enum AppTab: Hashable {
    case home
    case search
    case cart
    case user
}

/// 路由协调器：每个 Tab 独立导航栈，切 Tab 互不覆盖（plans/ios-frontend 1.4）。
@MainActor
final class AppRouter: ObservableObject {
    @Published var selectedTab: AppTab = .home
    /// 由首页热搜词等处投喂给搜索 Tab 的关键词（SearchView 消费后置回 nil）
    @Published var searchKeyword: String?
    @Published var homePath: [Route] = []
    @Published var searchPath: [Route] = []
    @Published var cartPath: [Route] = []
    @Published var userPath: [Route] = []

    /// 压入当前 Tab 的栈
    func push(_ route: Route) {
        switch selectedTab {
        case .home: homePath.append(route)
        case .search: searchPath.append(route)
        case .cart: cartPath.append(route)
        case .user: userPath.append(route)
        }
    }

    /// 切换 Tab 并压栈
    func push(_ route: Route, on tab: AppTab) {
        selectedTab = tab
        push(route)
    }

    func pop() {
        switch selectedTab {
        case .home: if !homePath.isEmpty { homePath.removeLast() }
        case .search: if !searchPath.isEmpty { searchPath.removeLast() }
        case .cart: if !cartPath.isEmpty { cartPath.removeLast() }
        case .user: if !userPath.isEmpty { userPath.removeLast() }
        }
    }

    func popToRoot() {
        homePath = []
        searchPath = []
        cartPath = []
        userPath = []
    }

    /// 替换栈顶（下单成功：结算页出栈、压入支付页）
    func replaceTop(with route: Route) {
        pop()
        push(route)
    }
}

/// 单个 Tab 的导航宿主（持有该 Tab 的路径绑定）
struct NavigationHost<Root: View>: View {
    @EnvironmentObject private var router: AppRouter

    let tab: AppTab
    let root: Root

    var body: some View {
        NavigationStack(path: pathBinding) {
            root
                .navigationDestination(for: Route.self) { route in
                    RouteDestination(route: route)
                }
        }
    }

    private var pathBinding: Binding<[Route]> {
        switch tab {
        case .home: return $router.homePath
        case .search: return $router.searchPath
        case .cart: return $router.cartPath
        case .user: return $router.userPath
        }
    }
}

/// Route → 目标视图（唯一映射点，新增页面只改这里）
struct RouteDestination: View {
    let route: Route

    var body: some View {
        switch route {
        case .productDetail(let spuId):
            ProductDetailView(spuId: spuId)
        case .checkout(let items):
            CheckoutView(items: items)
        case .pay(let orderId):
            PayView(orderId: orderId)
        case .orderList(let status):
            OrderListView(initialStatus: status)
        case .orderDetail(let orderId):
            OrderDetailView(orderId: orderId)
        case .addressList(let selecting):
            AddressListView(selecting: selecting)
        case .addressEdit(let addressId):
            AddressEditView(addressId: addressId)
        }
    }
}
