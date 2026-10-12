import Foundation

/// 地址列表逻辑：加载、删除、设默认（选择模式下回填 AppState）。
@MainActor
final class AddressViewModel: ObservableObject {

    @Published var addresses: [Address] = []
    @Published var isLoading = false
    @Published var message: String?

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let response: AddressListResponse = try await APIClient.shared.request(
                "/api/v1/user/addresses"
            )
            addresses = response.addresses
        } catch {
            message = error.localizedDescription
        }
    }

    /// 选中地址（结算页选择模式）
    func select(_ address: Address) {
        AppState.shared.selectedAddress = address
    }

    func delete(_ address: Address) async {
        do {
            let _: AddressFlagResponse = try await APIClient.shared.request(
                "/api/v1/user/addresses/\(address.id)",
                method: .delete
            )
            // 删除的是当前选中地址时清空回填
            if AppState.shared.selectedAddress?.id == address.id {
                AppState.shared.selectedAddress = nil
            }
            await load()
        } catch {
            message = error.localizedDescription
        }
    }

    func setDefault(_ address: Address) async {
        do {
            let _: AddressFlagResponse = try await APIClient.shared.request(
                "/api/v1/user/addresses/\(address.id)/default",
                method: .post,
                body: EmptyBody()
            )
            await load()
        } catch {
            message = error.localizedDescription
        }
    }

    /// 按 ID 查地址（编辑页复用列表数据源，服务端无单条查询接口）
    func find(_ addressId: Int64) -> Address? {
        addresses.first { $0.id == addressId }
    }
}

/// 地址编辑页逻辑：新增 / 编辑（PUT），保存后出栈。
@MainActor
final class AddressEditViewModel: ObservableObject {

    @Published var name = ""
    @Published var mobile = ""
    @Published var province = ""
    @Published var city = ""
    @Published var district = ""
    @Published var detail = ""
    @Published var zipCode = ""
    @Published var isDefault = false
    @Published var saving = false
    @Published var message: String?

    let addressId: Int64?

    init(addressId: Int64?) {
        self.addressId = addressId
    }

    var isEditing: Bool { addressId != nil }

    /// 编辑模式回填（服务端无单条地址接口，从列表数据里找）
    func fill(from address: Address?) {
        guard let address else { return }
        name = address.name
        // 列表出参手机号已脱敏，编辑时提交脱敏号会被校验拒绝 → 只保留前3位提示，要求重填
        mobile = address.mobile.contains("*") ? "" : address.mobile
        province = address.province
        city = address.city
        district = address.district ?? ""
        detail = address.detail
        zipCode = address.zipCode ?? ""
        isDefault = address.isDefault
    }

    /// 本地校验（与服务端 @Pattern 规则一致）
    private func validate() -> Bool {
        if name.trimmingCharacters(in: .whitespaces).isEmpty { message = "请填写收货人"; return false }
        if mobile.range(of: "^1[3-9]\\d{9}$", options: .regularExpression) == nil {
            message = "手机号格式不正确"; return false
        }
        if province.trimmingCharacters(in: .whitespaces).isEmpty { message = "请填写省份"; return false }
        if city.trimmingCharacters(in: .whitespaces).isEmpty { message = "请填写城市"; return false }
        if detail.trimmingCharacters(in: .whitespaces).isEmpty { message = "请填写详细地址"; return false }
        return true
    }

    func save(router: AppRouter) async {
        guard validate() else { return }
        guard !saving else { return }
        saving = true
        defer { saving = false }

        let body = AddressRequestBody(
            name: name.trimmingCharacters(in: .whitespaces),
            mobile: mobile,
            province: province.trimmingCharacters(in: .whitespaces),
            city: city.trimmingCharacters(in: .whitespaces),
            district: district.trimmingCharacters(in: .whitespaces),
            detail: detail.trimmingCharacters(in: .whitespaces),
            zipCode: zipCode.isEmpty ? nil : zipCode,
            isDefault: isDefault
        )

        do {
            if let addressId {
                let _: AddressFlagResponse = try await APIClient.shared.request(
                    "/api/v1/user/addresses/\(addressId)",
                    method: .put,
                    body: body
                )
            } else {
                let _: AddressIdResponse = try await APIClient.shared.request(
                    "/api/v1/user/addresses",
                    method: .post,
                    body: body
                )
            }
            router.pop()
        } catch {
            // 服务端 msg 直显（手机号格式、重复默认等）
            message = error.localizedDescription
        }
    }
}
