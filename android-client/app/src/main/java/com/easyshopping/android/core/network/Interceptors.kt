package com.easyshopping.android.core.network

import com.easyshopping.android.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody
import okio.Buffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 通用请求头拦截器：
 * 1. 注入 `Authorization: Bearer <accessToken>`；
 * 2. 写接口（POST/PUT/DELETE）注入 `Idempotency-Key` = UUID（服务端 @Idempotent 强制，
 *    缺失 → 1001；同一键重复 → 1005，见 DEV.md 4.2 步骤 5）；
 * 3. （骨架）签名头：HMAC-SHA256，±5 分钟容差 / Nonce TTL 10 分钟（android-backend 13.1）。
 *
 * 链路顺序：Signature(签名覆盖 body) → Idempotency → Auth → Log。
 */
class CommonHeadersInterceptor(
    private val session: SessionStore,
    private val signature: SignatureInterceptor = SignatureInterceptor(),
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // 1) 幂等键：写接口统一带上（服务端只在 @Idempotent 接口消费）
        if (original.method in WRITE_METHODS && original.header(HEADER_IDEMPOTENCY) == null) {
            builder.header(HEADER_IDEMPOTENCY, UUID.randomUUID().toString())
        }

        // 2) 登录态
        val token = session.accessToken
        if (!token.isNullOrBlank() && original.header("Authorization") == null) {
            builder.header("Authorization", "Bearer $token")
        }

        // 3) 签名（后端未开验签时 SignInterceptor 内部直接放行，见 BuildConfig.SIGN_ENABLED）
        signature.sign(builder, original)

        return chain.proceed(builder.build())
    }

    companion object {
        const val HEADER_IDEMPOTENCY = "Idempotency-Key"
        private val WRITE_METHODS = setOf("POST", "PUT", "DELETE", "PATCH")
    }
}

/**
 * 接口签名拦截器（骨架）：待签串 `method\npath\ntimestamp\nnonce\nsortedQuery\nbodySha256`，
 * HMAC-SHA256（plans/android-backend.md 13.1）。
 *
 * **开关位置**：`app/build.gradle.kts` 的 `SIGN_ENABLED`（对应 `BuildConfig.SIGN_ENABLED`）。
 * 后端 edge-gateway 尚未开验签（TODO(P0) W2 联调），P0 默认 false 直接放行；
 * 联调开启时：网关需同步实现 timestamp ±5min 校验 + nonce Redis SET NX（TTL 10min 去重）。
 *
 * secret 随登录响应下发并存 Keystore —— P0 后端未下发 signSecret，先留本地占位（TODO）。
 */
class SignatureInterceptor {

    /** P0 占位：后端下发 secret 后改从 SessionStore/Keystore 读取。 */
    private var secret: String? = null

    fun sign(builder: okhttp3.Request.Builder, request: okhttp3.Request) {
        if (!BuildConfig.SIGN_ENABLED) return   // 后端未开验签 → 放行

        val s = secret ?: return               // 无 secret → 放行（登录后下发后启用）
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val bodySha256 = request.body?.sha256() ?: ""
        val sortedQuery = request.url.queryParameterNames.sorted().joinToString("&") { k ->
            "$k=${request.url.queryParameter(k) ?: ""}"
        }
        val pending = listOf(
            request.method, request.url.encodedPath, timestamp, nonce, sortedQuery, bodySha256
        ).joinToString("\n")
        val sign = hmacSha256(s, pending)

        builder.header("X-Timestamp", timestamp)
            .header("X-Nonce", nonce)
            .header("X-Sign", sign)

        // TODO(P0 W2 联调)：Nonce 去重由网关 Redis SET NX 负责（TTL = 2 × 时间戳容差 = 10min），
        // 客户端只负责生成与签名覆盖，不落地本地 nonce 表。
    }

    private fun RequestBody.sha256(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return sha256Hex(buffer.readByteArray())
    }

    private fun hmacSha256(key: String, pending: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(pending.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
