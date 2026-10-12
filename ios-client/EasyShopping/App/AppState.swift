import SwiftUI

/// 全局业务状态：登录态（游客可浏览，关键动作前置登录）、购物车角标、结算页选中地址。
/// （plans/ios-frontend 2.1：不做强制登录墙，加购/下单/看个人信息时再弹登录。）
@MainActor
final class AppState: ObservableObject {

    static let shared = AppState()

    @Published var isLoggedIn = false
    /// 触发登录弹窗（RootView 监听并 present AuthView）
    @Published var loginRequired = false
    /// 购物车角标（服务端 totalQuantity）
    @Published var cartCount = 0
    /// 结算页选中的收货地址（地址列表选择模式回填）
    @Published var selectedAddress: Address?

    private init() {}

    // MARK: 启动恢复

    /// 冷启动：Access Token 只在内存，用持久化的 Refresh Token 换一枚新的。
    func bootstrap() async {
        guard APIClient.shared.hasRefreshToken else { return }
        if await APIClient.shared.restoreSession() {
            isLoggedIn = true
            await refreshCartCount()
        } else {
            isLoggedIn = false
        }
    }

    // MARK: 登录

    /// 需要登录的动作先过这里；未登录则拉起登录弹窗并返回 false。
    @discardableResult
    func requireLogin() -> Bool {
        if isLoggedIn { return true }
        loginRequired = true
        return false
    }

    func completeLogin(_ token: TokenResponse) {
        APIClient.shared.setAccessToken(token.accessToken)
        if let refresh = token.refreshToken {
            APIClient.shared.storedRefreshToken = refresh
        }
        isLoggedIn = true
        loginRequired = false
        selectedAddress = nil
        Task { await refreshCartCount() }
    }

    /// 退出登录：服务端吊销 Refresh Token + 全量清理本地状态。
    func logout() async {
        struct LogoutBody: Encodable {
            let refreshToken: String?
        }
        struct LogoutResponse: Decodable {
            let success: Bool?
        }
        if let refresh = APIClient.shared.storedRefreshToken {
            do {
                let _: LogoutResponse = try await APIClient.shared.request(
                    "/api/v1/auth/logout",
                    method: .post,
                    body: LogoutBody(refreshToken: refresh)
                )
            } catch {
                // 吊销失败也继续本地登出（服务端 Refresh Token 会自然过期）
            }
        }
        clearLocalSession()
    }

    /// 刷新失败（1002/1003 且换不到新 Token）触发的被动登出。
    func forceLogout() {
        clearLocalSession()
    }

    private func clearLocalSession() {
        APIClient.shared.clearTokens()
        isLoggedIn = false
        cartCount = 0
        selectedAddress = nil
    }

    // MARK: 购物车角标

    func refreshCartCount() async {
        guard isLoggedIn else {
            cartCount = 0
            return
        }
        do {
            let cart: CartList = try await APIClient.shared.request("/api/v1/cart")
            cartCount = cart.totalQuantity ?? 0
        } catch {
            // 角标刷新失败静默（不影响主流程）
        }
    }
}
