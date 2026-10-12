import Foundation

@MainActor
final class AuthViewModel: ObservableObject {

    enum Mode: Hashable {
        case login
        case register
    }

    @Published var mode: Mode = .login

    // 登录
    @Published var username = ""
    @Published var password = ""

    // 注册
    @Published var mobile = ""
    @Published var code = ""

    @Published var loading = false
    @Published var message: String?

    var canSubmit: Bool {
        switch mode {
        case .login:
            return !username.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty
        case .register:
            return !mobile.isEmpty && !code.isEmpty && password.count >= 6
        }
    }

    func submit() async {
        guard canSubmit else {
            message = mode == .login ? "请输入账号和密码" : "请填写手机号、验证码和密码（6~32 位）"
            return
        }
        loading = true
        defer { loading = false }

        do {
            let token: TokenResponse
            switch mode {
            case .login:
                token = try await APIClient.shared.request(
                    "/api/v1/auth/login",
                    method: .post,
                    body: LoginBody(username: username.trimmingCharacters(in: .whitespaces),
                                    password: password)
                )
            case .register:
                token = try await APIClient.shared.request(
                    "/api/v1/auth/register",
                    method: .post,
                    body: RegisterBody(mobile: mobile, code: code, password: password)
                )
            }
            guard token.accessToken != nil else {
                message = "登录响应缺少 accessToken"
                return
            }
            AppState.shared.completeLogin(token)
        } catch {
            // 错误文案直显服务端 msg（3002/券失效等同口径）
            message = error.localizedDescription
        }
    }
}
