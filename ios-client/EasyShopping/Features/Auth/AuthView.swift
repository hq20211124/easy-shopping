import SwiftUI

/// 登录 / 注册（以 sheet 形式在需要登录的动作上弹出）。
/// P0 口径：用户名（手机号/邮箱）+ 密码登录；手机号 + 短信验证码注册（服务端固定码 123456）。
struct AuthView: View {
    @EnvironmentObject private var appState: AppState
    @StateObject private var vm = AuthViewModel()

    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case username
        case password
        case mobile
        case code
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 8) {
                        Image(systemName: "cart.fill")
                            .font(.system(size: 44))
                            .foregroundColor(AppTheme.primary)
                        Text("欢迎来到易购")
                            .font(.title2.bold())
                        Text("登录后可加购、下单、管理收货地址")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.top, 24)

                    Picker("模式", selection: $vm.mode) {
                        Text("登录").tag(AuthViewModel.Mode.login)
                        Text("注册").tag(AuthViewModel.Mode.register)
                    }
                    .pickerStyle(.segmented)

                    switch vm.mode {
                    case .login:
                        loginForm
                    case .register:
                        registerForm
                    }

                    if let message = vm.message {
                        Text(message)
                            .font(.subheadline)
                            .foregroundColor(AppTheme.primary)
                            .multilineTextAlignment(.center)
                    }

                    Button {
                        focusedField = nil
                        Task { await vm.submit() }
                    } label: {
                        Group {
                            if vm.loading {
                                ProgressView().tint(.white)
                            } else {
                                Text(vm.mode == .login ? "登录" : "注册并登录")
                                    .font(.headline)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(vm.canSubmit ? AppTheme.primary : Color.gray.opacity(0.5))
                        .foregroundColor(.white)
                        .cornerRadius(24)
                    }
                    .disabled(!vm.canSubmit || vm.loading)

                    Text("登录即代表同意《用户协议》与《隐私政策》")
                        .font(.caption2)
                        .foregroundColor(.secondary)

                    Spacer(minLength: 24)
                }
                .padding(.horizontal, 20)
            }
            .background(AppTheme.background)
            .navigationTitle(vm.mode == .login ? "登录" : "注册")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") {
                        appState.loginRequired = false
                    }
                }
            }
        }
    }

    private var loginForm: some View {
        VStack(spacing: 14) {
            field(icon: "person", placeholder: "手机号 / 邮箱", text: $vm.username)
                .focused($focusedField, equals: .username)
                .textContentType(.username)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)

            SecureField("密码", text: $vm.password)
                .inputFieldStyle()
                .focused($focusedField, equals: .password)
                .textContentType(.password)
        }
    }

    private var registerForm: some View {
        VStack(spacing: 14) {
            field(icon: "iphone", placeholder: "手机号", text: $vm.mobile)
                .focused($focusedField, equals: .mobile)
                .keyboardType(.numberPad)
                .textContentType(.telephoneNumber)

            field(icon: "number", placeholder: "短信验证码（P0 固定 123456）", text: $vm.code)
                .focused($focusedField, equals: .code)
                .keyboardType(.numberPad)

            SecureField("设置密码（6~32 位）", text: $vm.password)
                .inputFieldStyle()
                .textContentType(.newPassword)
        }
    }

    private func field(icon: String, placeholder: String, text: Binding<String>) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .foregroundColor(.secondary)
                .frame(width: 20)
            TextField(placeholder, text: text)
        }
        .inputFieldStyle()
    }
}

private extension View {
    /// 统一输入框底样式
    func inputFieldStyle() -> some View {
        padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(AppTheme.card)
            .cornerRadius(10)
    }
}
