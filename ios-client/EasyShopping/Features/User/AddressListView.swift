import SwiftUI

/// 地址列表：selecting=true 为结算页选择模式（点选回填并出栈），
/// 否则为管理入口（点击进编辑、滑动删除、设默认）。
struct AddressListView: View {
    let selecting: Bool

    @EnvironmentObject private var router: AppRouter
    @StateObject private var vm = AddressViewModel()

    var body: some View {
        Group {
            if vm.isLoading && vm.addresses.isEmpty {
                LoadingView()
            } else if vm.addresses.isEmpty {
                EmptyStateView(title: "还没有收货地址",
                               systemImage: "mappin.and.ellipse",
                               actionTitle: "新增地址") {
                    router.push(.addressEdit(addressId: nil))
                }
            } else {
                list
            }
        }
        .background(AppTheme.background)
        .navigationTitle(selecting ? "选择地址" : "收货地址")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    router.push(.addressEdit(addressId: nil))
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .toast($vm.message)
        .task {
            await vm.load()
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                ForEach(vm.addresses) { address in
                    row(address)
                }
            }
            .padding(12)
        }
    }

    private func row(_ address: Address) -> some View {
        HStack(spacing: 10) {
            if selecting {
                Image(systemName: "circle")
                    .foregroundColor(.secondary)
                    .onTapGesture {
                        finishSelect(address)
                    }
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 10) {
                    Text(address.name)
                        .font(.subheadline.bold())
                    Text(address.mobile)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    if address.isDefault {
                        StatusChip(text: "默认", color: AppTheme.primary)
                    }
                    Spacer()
                }
                Text(address.fullAddress)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .onTapGesture {
                finishSelect(address)
            }

            if !selecting {
                Menu {
                    Button("编辑") {
                        router.push(.addressEdit(addressId: address.id))
                    }
                    Button("设为默认") {
                        Task { await vm.setDefault(address) }
                    }
                    Button("删除", role: .destructive) {
                        Task { await vm.delete(address) }
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .foregroundColor(.secondary)
                        .frame(width: 32, height: 32)
                }
            }
        }
        .padding(12)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    /// 选择模式：回填全局选中地址并返回结算页
    private func finishSelect(_ address: Address) {
        guard selecting else { return }
        vm.select(address)
        router.pop()
    }
}
