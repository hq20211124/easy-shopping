import Foundation

// MARK: - 通知

extension Notification.Name {
    /// 会话失效（刷新 Token 也救不回来）→ RootView 收敛为登出并拉起登录
    static let sessionExpired = Notification.Name("EasyShopping.sessionExpired")
}

// MARK: - HTTP 方法

enum HTTPMethod: String {
    case get = "GET"
    case post = "POST"
    case put = "PUT"
    case delete = "DELETE"
}

// MARK: - 信封

/// 统一响应信封 `{code, msg, data, traceId}`（见 DEV.md 3 与 server/common ApiResponse）。
/// 业务错误同样走该信封，只是 HTTP 状态非 200（401/403/404/409），因此先解信封再看状态码。
struct ApiResponse<T: Decodable>: Decodable {
    let code: Int
    let msg: String?
    let data: T?
    let traceId: String?
}

/// 分页信封 `{total, pageNum, pageSize, list}`（server/common PageResult）。
struct PageResult<T: Decodable>: Decodable {
    let total: Int64
    let pageNum: Int
    let pageSize: Int
    let list: [T]
}

/// 无请求体占位（DELETE / 无 body 的 POST 使用）。
struct EmptyBody: Encodable {}

/// 刷新 Token 响应里的最小载荷。
struct TokenPayload: Decodable {
    let accessToken: String?
    let refreshToken: String?
}

// MARK: - 错误

enum APIError: LocalizedError {
    /// 请求 URL 拼装失败
    case invalidURL
    /// 网络层失败（断网、超时、DNS）
    case transport(String)
    /// 响应不是约定信封 / data 缺失
    case decode(String)
    /// 刷新 Token 后仍失败 → 需要重新登录
    case notLoggedIn
    /// 业务错误：code != 0（toast 文案直接用 message，如 3002 库存不足 / 券失效）
    case server(code: Int, message: String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "请求地址无效"
        case .transport(let detail):
            return "网络异常：\(detail)"
        case .decode(let detail):
            return "数据解析失败（\(detail)）"
        case .notLoggedIn:
            return "登录已失效，请重新登录"
        case .server(_, let message):
            return message
        }
    }

    /// 业务错误码（非业务错误返回 nil）
    var serverCode: Int? {
        if case .server(let code, _) = self {
            return code
        }
        return nil
    }
}

// MARK: - 客户端

/// 全局唯一的网络入口：async/await + URLSession，零三方依赖。
///
/// 关键口径：
/// - Access Token **仅存内存**，Refresh Token 持久化（plans/ios-frontend 3.2）；
/// - 收到 401 / 业务码 1002（未登录）/ 1003（Token 过期）→ 单飞刷新，**只重试一次**；
/// - 写接口（非 GET）自动携带 `Idempotency-Key: UUID().uuidString`，401 重试复用同一个键；
/// - 业务错误以 `msg` 透传给 UI（3002 库存不足、券失效等直显，禁止前端改写文案）；
/// - **解码失败必须抛出可读错误并上抛到 UI**，禁止 `try?` 静默（见 `perform` 内的说明）。
///
/// ## 并发标注（改动前必读）
/// - 本类型标了 `@MainActor`：单例 + 可变状态（accessToken / refreshTask / storedRefreshToken）
///   在 `@MainActor` 下天然串行，既消除 `refreshPayload()` 里"判空与赋值之间无同步"的竞态
///   （多个并发 401 各自发起 refresh → refresh token 轮转把用户误踢下线，见 AUDIT P1-3），
///   也让状态访问有明确的隔离域。所有调用方都是 `@MainActor` 的 ViewModel，
///   `await APIClient.shared.request(...)` 的写法无需改动。
/// - ⚠️ **语言模式约束**：`project.yml` 设 `SWIFT_VERSION: "5.0"` 且**未开严格并发**
///   （`SWIFT_STRICT_CONCURRENCY` 显式设 `minimal`）。切到 Swift 6 语言模式或
///   `complete` 检查时，`URLSession` 之外的 `Data`/`NotificationCenter` 传参与
///   各 View 的 `Task {}` 闭包会成片报错——那是**预期内**的迁移成本，不是回归。
@MainActor
final class APIClient {

    static let shared = APIClient()

    /// P0 联调地址：模拟器直连本机网关（Info.plist 已放行 localhost 的本地网络请求）。
    /// 真机调试改为开发机局域网 IP，生产为 https 域名。
    var baseURL: String = "http://localhost:8080"

    /// Access Token：只在内存，进程重启后由 refresh 恢复。
    private(set) var accessToken: String?

    /// UserDefaults 键名集中定义（避免魔法字符串散落；P1 迁 Keychain 时只改这一处）。
    /// 由 GitHub Actions macOS runner 首次编译验证补回——`@MainActor` 重构时遗漏了本定义。
    private enum TokenKeys {
        static let refreshToken = "es.refreshToken"
    }

    /// Refresh Token：持久化（P0 用 UserDefaults，P1 应迁 Keychain，见 plans/ios-frontend 3.2）。
    var storedRefreshToken: String? {
        get { UserDefaults.standard.string(forKey: TokenKeys.refreshToken) }
        set { UserDefaults.standard.set(newValue, forKey: TokenKeys.refreshToken) }
    }

    /// 单飞锁：并发 401 只刷新一次（避免 refresh token 轮转把后续请求打挂）。
    private var refreshTask: Task<TokenPayload?, Never>?

    private let session: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        session = URLSession(configuration: config)
    }

    // MARK: Token

    func setAccessToken(_ token: String?) {
        accessToken = token
    }

    func clearTokens() {
        accessToken = nil
        UserDefaults.standard.removeObject(forKey: TokenKeys.refreshToken)
    }

    var hasRefreshToken: Bool {
        storedRefreshToken != nil
    }

    /// 冷启动恢复会话：Access Token 已随进程丢失，用 Refresh Token 换一枚新的。
    /// 成功返回 true（并已写入 accessToken）。
    func restoreSession() async -> Bool {
        guard hasRefreshToken else { return false }
        guard let payload = await refreshPayload() else {
            clearTokens()
            return false
        }
        return payload.accessToken != nil
    }

    // MARK: 请求（无请求体）

    func request<T: Decodable>(_ path: String,
                               method: HTTPMethod = .get,
                               query: [URLQueryItem] = [],
                               retryOnAuth: Bool = true) async throws -> T {
        let key = idempotencyKey(for: method)
        return try await perform(path, method: method, query: query,
                                 bodyData: nil, idempotencyKey: key, retryOnAuth: retryOnAuth)
    }

    // MARK: 请求（带请求体，body 自动 JSON 编码）

    func request<T: Decodable, B: Encodable>(_ path: String,
                                             method: HTTPMethod,
                                             body: B,
                                             query: [URLQueryItem] = [],
                                             retryOnAuth: Bool = true) async throws -> T {
        var data: Data?
        if !(body is EmptyBody) {
            data = try JSONEncoder().encode(body)
        }
        let key = idempotencyKey(for: method)
        return try await perform(path, method: method, query: query,
                                 bodyData: data, idempotencyKey: key, retryOnAuth: retryOnAuth)
    }

    private func idempotencyKey(for method: HTTPMethod) -> String? {
        // 写接口必带幂等键（server 侧 @Idempotent 强制，缺键 1001）
        method == .get ? nil : UUID().uuidString
    }

    // MARK: 执行

    private func perform<T: Decodable>(_ path: String,
                                       method: HTTPMethod,
                                       query: [URLQueryItem],
                                       bodyData: Data?,
                                       idempotencyKey: String?,
                                       retryOnAuth: Bool) async throws -> T {
        var components = URLComponents(string: baseURL + path)
        if !query.isEmpty {
            components?.queryItems = query
        }
        guard let url = components?.url else { throw APIError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let key = idempotencyKey {
            request.setValue(key, forHTTPHeaderField: "Idempotency-Key")
        }
        if let bodyData = bodyData {
            request.httpBody = bodyData
        }

        let data: Data
        let status: Int
        do {
            let (raw, response) = try await session.data(for: request)
            data = raw
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
        } catch {
            throw APIError.transport(error.localizedDescription)
        }

        let envelope: ApiResponse<T>
        do {
            envelope = try JSONDecoder().decode(ApiResponse<T>.self, from: data)
        } catch {
            // P0-4 ①：禁止 `try?` 静默吞掉解码错误。
            // 本项目最大的一类事故就是"模型字段类型与后端相反"（如 PrepayResponse.payParams
            // 声明 String 后端下发 Map、CheckoutCoupon.status 声明 Int 后端下发英文串），
            // JSONDecoder 直接抛 typeMismatch，被 try? 吞掉后只剩一句
            // "数据解析失败（HTTP 200）"，支付页 100% 失败、券区恒空且用户零感知。
            // 这里把 DecodingError 的字段路径 + 响应体片段一并抛出，联调时可直接定位到是哪个字段。
            throw APIError.decode("HTTP \(status)：\(APIClient.describe(error))；响应片段 \(APIClient.snippet(data))")
        }

        if envelope.code == 0 {
            guard let payload = envelope.data else { throw APIError.decode("data 为空") }
            return payload
        }

        let message = envelope.msg ?? "请求失败"

        // 401 / 1002 未登录 / 1003 Token 过期 → 刷新一次后重试（只重试一次）
        // 注意逗号是「条件列表」分隔符，实际语义是 `(retryOnAuth) && (status==401 || code==1002 || code==1003)`，
        // 不是运算符优先级问题。
        if retryOnAuth, status == 401 || envelope.code == 1002 || envelope.code == 1003 {
            if let payload = await refreshPayload(), payload.accessToken != nil {
                return try await perform(path, method: method, query: query,
                                         bodyData: bodyData, idempotencyKey: idempotencyKey,
                                         retryOnAuth: false)
            }
            clearTokens()
            NotificationCenter.default.post(name: .sessionExpired, object: nil)
            throw APIError.notLoggedIn
        }

        throw APIError.server(code: envelope.code, message: message)
    }

    // MARK: 解码失败的可读化（把"契约错配"变成能定位的一句话）

    /// 把 `DecodingError` 翻成"哪个字段、期望什么类型、实际是什么"的可读描述。
    /// 这是本项目契约事故的主要抓手：类型不匹配时能直接点名字段，不再只报一个 HTTP 码。
    private static func describe(_ error: Error) -> String {
        guard let decodingError = error as? DecodingError else {
            return "\(error)"
        }
        switch decodingError {
        case .typeMismatch(let type, let context):
            return "字段类型不匹配（期望 \(type)）@\(path(context))"
        case .valueNotFound(let type, let context):
            return "字段值为 null/缺失（期望 \(type)）@\(path(context))"
        case .keyNotFound(let key, let context):
            return "缺少字段 \(key.stringValue)@\(path(context))"
        case .dataCorrupted(let context):
            return "数据格式错误（\(context.debugDescription)）@\(path(context))"
        @unknown default:
            return "\(decodingError)"
        }
    }

    private static func path(_ context: DecodingError.Context) -> String {
        let joined = context.codingPath.map { $0.stringValue }.joined(separator: ".")
        return joined.isEmpty ? "data" : "data." + joined
    }

    /// 响应体片段（截断），便于直接看出后端到底下发了什么
    private static func snippet(_ data: Data, limit: Int = 240) -> String {
        guard let text = String(data: data, encoding: .utf8) else {
            return "<\(data.count) bytes 非 UTF-8>"
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "<空响应体>" }
        return trimmed.count <= limit ? trimmed : String(trimmed.prefix(limit)) + "…"
    }

    // MARK: 刷新（单飞）

    /// 单飞刷新：并发 401 只发一次 refresh。
    ///
    /// 竞态说明（AUDIT P1-3）：旧版本这里是**非隔离**的普通类方法，
    /// 「判 `refreshTask == nil`」与「赋值 `refreshTask = task`」之间没有同步点，
    /// 冷启动并发 3 个请求同时 401 时会各发一次 refresh；后端
    /// `AuthService.refresh` 会轮转 refresh token（P0 宽限 60s），
    /// 第 2、3 次拿旧 token 被拒 → `refreshPayload()` 返回 nil → `clearTokens()` +
    /// `.sessionExpired` → 用户刚登录就被踢回登录页。
    ///
    /// 现在整个类型标了 `@MainActor`，从读 `refreshTask` 到写 `refreshTask` 之间
    /// **没有 await 挂起点**，串行化由 actor 保证，单飞不再依赖运气。
    private func refreshPayload() async -> TokenPayload? {
        if let task = refreshTask {
            return await task.value
        }
        guard let refresh = storedRefreshToken else { return nil }

        let base = baseURL
        let task = Task<TokenPayload?, Never> {
            await APIClient.performRefresh(baseURL: base, refreshToken: refresh)
        }
        refreshTask = task
        let payload = await task.value
        refreshTask = nil

        if let payload = payload {
            if let access = payload.accessToken {
                accessToken = access
            }
            // Refresh Token 轮换：新值覆盖旧值
            if let rotated = payload.refreshToken {
                storedRefreshToken = rotated
            }
        }
        return payload
    }

    /// 独立实现，避免在 @Sendable 闭包里捕获 self（并发检查更干净）。
    ///
    /// 这里的 `try?` 是**有意保留**的：本函数的契约就是「换不到新 Token 就返回 nil」，
    /// 调用方 `perform` 会据此 `clearTokens()` + 发 `.sessionExpired` 收敛到重新登录，
    /// 不构成"静默降级"。用户可见路径上的解码一律走 `perform` 里的 `do/catch`。
    private static func performRefresh(baseURL: String, refreshToken: String) async -> TokenPayload? {
        guard let url = URL(string: baseURL + "/api/v1/auth/refresh") else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = HTTPMethod.post.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        struct RefreshBody: Encodable {
            let refreshToken: String
        }
        request.httpBody = try? JSONEncoder().encode(RefreshBody(refreshToken: refreshToken))

        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let envelope = try? JSONDecoder().decode(ApiResponse<TokenPayload>.self, from: data),
              envelope.code == 0,
              let payload = envelope.data else {
            return nil
        }
        return payload
    }
}
