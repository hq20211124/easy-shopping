import Foundation

/// 我的页逻辑：资料加载、退出登录。
@MainActor
final class ProfileViewModel: ObservableObject {

    @Published var profile: ProfileResponse?
    @Published var isLoading = false
    @Published var message: String?

    func load() async {
        guard AppState.shared.isLoggedIn else {
            profile = nil
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            let response: ProfileResponse = try await APIClient.shared.request("/api/v1/user/profile")
            profile = response
        } catch {
            message = error.localizedDescription
        }
    }

    /// 退出登录（服务端吊销 + 本地清理）
    func logout() async {
        await AppState.shared.logout()
        profile = nil
    }
}
