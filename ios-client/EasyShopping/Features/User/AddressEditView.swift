import SwiftUI

/// 地址编辑：新增 / 修改（addressId == nil 为新增）。
/// 注意：列表出参手机号已脱敏（138****8000），编辑时需重新填写明文手机号
/// （服务端无单条地址查询接口，明文只在创建时存在）。
struct AddressEditView: View {
    let addressId: Int64?

    @EnvironmentObject private var router: AppRouter
    @StateObject private var vm: AddressEditViewModel
    @StateObject private var listVM = AddressViewModel()

    init(addressId: Int64?) {
        self.addressId = addressId
        _vm = StateObject(wrappedValue: AddressEditViewModel(addressId: addressId))
    }

    var body: some View {
        Form {
            Section("收货人") {
                TextField("姓名", text: $vm.name)
                    .textContentType(.name)
                TextField("手机号", text: $vm.mobile)
                    .textContentType(.telephoneNumber)
                    .keyboardType(.numberPad)
            }

            Section("地址") {
                TextField("省", text: $vm.province)
                TextField("市", text: $vm.city)
                TextField("区（选填）", text: $vm.district)
                TextField("详细地址", text: $vm.detail)
                    .keyboardType(.default)
                TextField("邮编（选填）", text: $vm.zipCode)
                    .keyboardType(.numberPad)
            }

            Section {
                Toggle("设为默认地址", isOn: $vm.isDefault)
            } footer: {
                if addressId != nil {
                    Text("编辑时手机号需重新填写（列表仅返回脱敏号码）")
                        .font(.caption)
                }
            }

            Section {
                Button {
                    Task { await vm.save(router: router) }
                } label: {
                    Group {
                        if vm.saving {
                            ProgressView()
                        } else {
                            Text("保存")
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .disabled(vm.saving)
            }
        }
        .navigationTitle(vm.isEditing ? "编辑地址" : "新增地址")
        .navigationBarTitleDisplayMode(.inline)
        .toast($vm.message)
        .task {
            // 编辑模式回填（复用列表数据源找该条地址）
            if addressId != nil {
                await listVM.load()
                vm.fill(from: listVM.find(addressId ?? 0))
            }
        }
    }
}
