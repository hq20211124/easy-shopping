import SwiftUI

@main
struct EasyShoppingApp: App {
    @StateObject private var appState = AppState.shared
    @StateObject private var router = AppRouter()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .environmentObject(router)
                .task {
                    // 冷启动恢复会话（Refresh Token → Access Token）
                    await appState.bootstrap()
                }
        }
    }
}

/// 根视图：登录弹窗 + 会话失效收敛。
struct RootView: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        MainTabView()
            .sheet(isPresented: $appState.loginRequired) {
                AuthView()
            }
            .onReceive(NotificationCenter.default.publisher(for: .sessionExpired)) { _ in
                appState.forceLogout()
            }
    }
}

/// 底部四个 Tab：首页 / 搜索 / 购物车 / 我的，各自独立 NavigationStack。
struct MainTabView: View {
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var appState: AppState

    var body: some View {
        TabView(selection: $router.selectedTab) {
            NavigationHost(tab: .home, root: HomeView())
                .tabItem {
                    Label("首页", systemImage: "house")
                }
                .tag(AppTab.home)

            NavigationHost(tab: .search, root: SearchView())
                .tabItem {
                    Label("搜索", systemImage: "magnifyingglass")
                }
                .tag(AppTab.search)

            NavigationHost(tab: .cart, root: CartView())
                .tabItem {
                    Label("购物车", systemImage: "cart")
                }
                .tag(AppTab.cart)
                .badge(appState.cartCount)

            NavigationHost(tab: .user, root: ProfileView())
                .tabItem {
                    Label("我的", systemImage: "person")
                }
                .tag(AppTab.user)
        }
        .tint(AppTheme.primary)
        .onChange(of: appState.isLoggedIn) { loggedIn in
            if loggedIn {
                Task { await appState.refreshCartCount() }
            }
        }
    }
}
