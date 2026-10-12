package com.easyshopping.android.core.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 401 单飞刷新（plans/android-frontend.md 1.6.4）：
 * 并发请求同时收到 401 时只发一次 refresh，其余请求等待复用结果；刷新失败清态并广播踢出事件。
 */
class TokenAuthenticator(
    private val session: SessionStore,
    private val refreshCall: (refreshToken: String) -> TokenResponse?,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // 只重试一次，避免 401 死循环
        if (responseCount(response) >= 2) {
            forceLogout("登录状态已失效")
            return null
        }
        val refreshing = session.refreshToken
        if (refreshing.isNullOrBlank()) {
            forceLogout("登录状态已失效")
            return null
        }

        val refreshed = singleFlightRefresh(refreshing) ?: run {
            forceLogout("登录已过期，请重新登录")
            return null
        }

        val newToken = refreshed.accessToken
        if (newToken.isNullOrBlank()) {
            forceLogout("登录已过期，请重新登录")
            return null
        }
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    /** 单飞：同一时刻只允许一个刷新在途，后到者等待锁并复用结果（不重复消费 refresh token）。 */
    private val lock = Any()

    private fun singleFlightRefresh(refreshToken: String): TokenResponse? {
        synchronized(lock) {
            // 排队期间别的请求已刷新过 → 直接复用新 token，不再次消费旧 refresh token
            val current = session.refreshToken
            if (current != null && current != refreshToken && !session.accessToken.isNullOrBlank()) {
                return TokenResponse(
                    userId = session.userId,
                    accessToken = session.accessToken,
                    refreshToken = current,
                )
            }
            val result = runCatching { refreshCall(refreshToken) }.getOrNull()
            if (result != null && !result.accessToken.isNullOrBlank()) {
                session.accessToken = result.accessToken
                session.refreshToken = result.refreshToken ?: session.refreshToken
                result.userId?.let { session.userId = it }
            } else {
                return null
            }
            return result
        }
    }

    private fun forceLogout(reason: String) {
        session.clear()
        SessionEvents.notify(SessionEvents.Event.KickedOut(reason))
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }
}

/** 登录/刷新/登出出入参（对齐 core-user AuthDtos.TokenResponse，camelCase）。 */
@kotlinx.serialization.Serializable
data class TokenResponse(
    val userId: Long? = null,
    val username: String? = null,
    val nickname: String? = null,
    val roles: List<String> = emptyList(),
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
    val tokenType: String? = null,
    val isNewUser: Boolean? = null,
)

/**
 * 全局会话事件（被踢/刷新失败）→ UI 层收集后跳登录。
 * 放网络层是为了让 Authenticator 这种非 ViewModel 上下文也能广播。
 */
object SessionEvents {
    sealed interface Event {
        data class KickedOut(val reason: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun notify(event: Event) {
        _events.tryEmit(event)
    }
}
