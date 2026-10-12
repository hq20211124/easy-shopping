import Foundation

// MARK: - 收货地址（server/core-user AddressDtos）

/// 地址（`GET /api/v1/user/addresses` 出参；mobile 服务端脱敏）。
/// AppState.selectedAddress 用它承载结算页选中地址。
struct Address: Codable, Identifiable, Hashable {
    let id: Int64
    let name: String
    let mobile: String
    let province: String
    let city: String
    let district: String?
    let detail: String
    let zipCode: String?
    let isDefault: Bool

    /// 拼接完整地址（省市区 + 详细地址）
    var fullAddress: String {
        [province, city, district ?? "", detail]
            .filter { !$0.isEmpty }
            .joined()
    }

    enum CodingKeys: String, CodingKey {
        case id, name, mobile, province, city, district, detail, zipCode, isDefault
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        mobile = try c.decode(String.self, forKey: .mobile)
        province = try c.decode(String.self, forKey: .province)
        city = try c.decode(String.self, forKey: .city)
        district = try c.decodeIfPresent(String.self, forKey: .district)
        detail = try c.decode(String.self, forKey: .detail)
        zipCode = try c.decodeIfPresent(String.self, forKey: .zipCode)
        isDefault = try c.decodeIfPresent(Bool.self, forKey: .isDefault) ?? false
    }
}

/// GET/POST/PUT /api/v1/user/addresses 响应包装
struct AddressListResponse: Decodable {
    let addresses: [Address]
}

struct AddressIdResponse: Decodable {
    let addressId: Int64?
}

struct AddressFlagResponse: Decodable {
    let updated: Bool?
    let deleted: Bool?
    let success: Bool?
}

/// POST/PUT 请求体（server AddressRequest；经纬度 P0 不传）
struct AddressRequestBody: Encodable {
    let name: String
    let mobile: String
    let province: String
    let city: String
    let district: String?
    let detail: String
    let zipCode: String?
    let isDefault: Bool
}

// MARK: - 个人资料（server/core-user UserDtos.ProfileResponse）

/// GET /api/v1/user/profile（手机号/邮箱脱敏出参）
struct ProfileResponse: Decodable {
    let userId: Int64?
    let username: String?
    let nickname: String?
    let avatar: String?
    let mobile: String?
    let email: String?
    /// 0未设置 1男 2女
    let gender: Int?
    /// yyyy-MM-dd
    let birthday: String?
    let registerDate: String?
    let level: Int?
    let levelName: String?
    let points: Int?
    let verified: Bool?

    /// 展示名：昵称 > 用户名
    var displayName: String {
        if let nickname, !nickname.isEmpty { return nickname }
        if let username, !username.isEmpty { return username }
        return "未设置昵称"
    }
}
