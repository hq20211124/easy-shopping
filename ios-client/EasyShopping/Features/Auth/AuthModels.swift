import Foundation

/// 登录/注册/刷新 统一返回体（server/core-user AuthDtos.TokenResponse，camelCase）。
struct TokenResponse: Decodable {
    let userId: Int64?
    let username: String?
    let nickname: String?
    let roles: [String]?
    let accessToken: String?
    let refreshToken: String?
    let expiresIn: Int64?
    let tokenType: String?
    let isNewUser: Bool?
}

/// POST /api/v1/auth/login
struct LoginBody: Encodable {
    let username: String
    let password: String
}

/// POST /api/v1/auth/register（P0 短信验证码固定 123456，见 core-user README）
struct RegisterBody: Encodable {
    let mobile: String
    let code: String
    let password: String
}

/// GET /api/v1/auth/me（联调自检）
struct MeResponse: Decodable {
    let userId: Int64?
}
