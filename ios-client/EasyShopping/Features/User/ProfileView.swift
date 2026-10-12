import SwiftUI

/// 我的：资料卡 + 我的订单（状态 Tab 快捷入口）+ 收货地址 + 关于。
struct ProfileView: View {
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var appState: AppState
    @StateObject private var vm = ProfileViewModel()

    @State private var showLogoutConfirm = false

    var body: some View {
        Group {
            if appState.isLoggedIn {
                content
            } else {
                guestView
            }
        }
        .background(AppTheme.background)
        .navigationTitle("我的")
        .navigationBarTitleDisplayMode(.inline)
        .toast($vm.message)
        .task {
            await vm.load()
        }
        .onChange(of: appState.isLoggedIn) { loggedIn in
            if loggedIn {
                Task { await vm.load() }
            }
        }
    }

    // MARK: 未登录

    private var guestView: some View {
        VStack(spacing: 16) {
            Image(systemName: "person.crop.circle")
                .font(.system(size: 64))
                .foregroundColor(.secondary)
            Text("登录后查看个人信息")
                .font(.subheadline)
                .foregroundColor(.secondary)
            Button("登录 / 注册") {
                appState.loginRequired = true
            }
            .font(.subheadline.bold())
            .padding(.horizontal, 30)
            .padding(.vertical, 11)
            .background(AppTheme.primary)
            .foregroundColor(.white)
            .cornerRadius(20)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: 已登录

    private var content: some View {
        ScrollView {
            VStack(spacing: 12) {
                profileCard
                orderSection
                miscSection
            }
            .padding(12)
        }
    }

    private var profileCard: some View {
        HStack(spacing: 12) {
            RemoteImage(urlString: vm.profile?.avatar)
                .frame(width: 56, height: 56)
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(vm.profile?.displayName ?? "…")
                    .font(.headline)
                if let mobile = vm.profile?.mobile {
                    Text(mobile)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                if let levelName = vm.profile?.levelName {
                    StatusChip(text: levelName, color: AppTheme.primary)
                }
            }
            Spacer()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    // MARK: 我的订单

    private var orderSection: some View {
        VStack(spacing: 10) {
            HStack {
                Text("我的订单")
                    .font(.subheadline.bold())
                Spacer()
                Button("全部") {
                    router.push(.orderList(status: nil))
                }
                .font(.caption)
                    .foregroundColor(.secondary)
            }

            HStack(spacing: 0) {
                orderEntry("待付款", "clock", 0)
                orderEntry("待发货", "shippingbox", 1)
                orderEntry("待收货", "truck.box", 2)
                orderEntry("已完成", "checkmark.seal", 3)
            }
        }
        .padding(14)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    private func orderEntry(_ title: String, _ symbol: String, _ status: Int) -> some View {
        Button {
            router.push(.orderList(status: status))
        } label: {
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.title3)
                    .foregroundColor(AppTheme.primary)
                Text(title)
                    .font(.caption)
                    .foregroundColor(.primary)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }

    // MARK: 其他

    @ViewBuilder
    private var miscSection: some View {
        VStack(spacing: 0) {
            miscRow("收货地址", "mappin.and.ellipse") {
                router.push(.addressList(selecting: false))
            }
            Divider().padding(.leading, 44)
            miscRow("设置", "gearshape") {
                vm.message = "设置页 P1 接入"
            }
            Divider().padding(.leading, 44)
            miscRow("关于易购", "info.circle") {
                vm.message = "易购 iOS P0 · 零三方依赖"
            }
        }
        .padding(.horizontal, 14)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)

        // 退出登录
        Button(role: .destructive) {
            showLogoutConfirm = true
        } label: {
            Text("退出登录")
                .font(.subheadline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(AppTheme.card)
                .foregroundColor(AppTheme.primary)
                .cornerRadius(AppTheme.cornerRadius)
        }
        .buttonStyle(.plain)
        .confirmationDialog("确定退出登录？", isPresented: $showLogoutConfirm, titleVisibility: .visible) {
            Button("退出登录", role: .destructive) {
                Task { await vm.logout() }
            }
            Button("取消", role: .cancel) {}
        }
    }

    private func miscRow(_ title: String, _ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .foregroundColor(AppTheme.primary)
                    .frame(width: 22)
                Text(title)
                    .font(.subheadline)
                    .foregroundColor(.primary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
    }
}
