package com.easyshopping.android.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 统一响应信封。骨架真值为 {code, msg, data, traceId}（DEV.md 3），
 * plans/android-frontend.md 1.6.3 写的是 message/timestamp/requestId —— 两个字段名都兼容，
 * 真值以服务端为准，不要按计划文档臆断字段名。
 */
@Serializable
data class ApiResponse<T>(
    val code: Int = -1,
    val msg: String? = null,
    val message: String? = null,
    val data: T? = null,
    @SerialName("traceId") val traceId: String? = null,
) {
    val text: String get() = msg ?: message ?: "未知错误"
    val isSuccess: Boolean get() = code == 0
}

/** 业务错误（code != 0）或网络/解析失败统一抛给 UI 层展示。 */
class ApiException(
    val code: Int,
    override val message: String,
    /** HTTP 状态码（网络层异常时为 0） */
    val httpCode: Int = 0,
) : Exception(message)

/**
 * 错误码白名单（plans/android-frontend.md 1.6.3 + server BizErrorCode）。
 * 语义变更需先更新 plans 16.2 并同步本表。
 */
object ErrorCode {
    const val SUCCESS = 0
    const val PARAM_ERROR = 1001        // 参数错误（含签名/时间戳/nonce 校验失败）
    const val NOT_LOGIN = 1002          // 未登录
    const val TOKEN_EXPIRED = 1003      // Token 过期
    const val NO_PERMISSION = 1004      // 无权限
    const val IDEMPOTENT_CONFLICT = 1005 // 幂等冲突（重复提交）
    const val OUT_OF_STOCK = 3002       // 库存不足 / 券失效等资源类冲突 → 展示 msg 并局部刷新
    const val ORDER_STATUS_ERROR = 4002 // 订单状态错误 → 刷新订单
    const val SYSTEM_ERROR = 9999

    /** 需要重新登录的码。 */
    fun needRelogin(code: Int) = code == NOT_LOGIN || code == TOKEN_EXPIRED
}
