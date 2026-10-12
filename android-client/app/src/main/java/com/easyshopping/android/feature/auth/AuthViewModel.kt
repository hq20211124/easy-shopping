package com.easyshopping.android.feature.auth

import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.SessionStore
import com.easyshopping.android.core.network.LoginRequest
import com.easyshopping.android.core.network.RegisterRequest
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.launch

enum class AuthMode { Login, Register }

data class AuthUiState(
    val mode: AuthMode = AuthMode.Login,
    val username: String = "",
    val verifyCode: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String = "",
    /** 提示文案 + 单调递增 id，避免同文案不重复触发 Snackbar */
    val message: String = "",
    val messageId: Long = 0,
)

sealed interface AuthIntent {
    data class SwitchMode(val mode: AuthMode) : AuthIntent
    data class UsernameChanged(val value: String) : AuthIntent
    data class CodeChanged(val value: String) : AuthIntent
    data class PasswordChanged(val value: String) : AuthIntent
    data object Submit : AuthIntent
}

sealed interface AuthEffect {
    data object LoggedIn : AuthEffect
}

/**
 * 登录/注册 ViewModel。凭证入库走 SessionStore（P0 SharedPreferences，P1 Keystore/EncryptedSP）。
 * 注册验证码：P0 后端固定 123456（core-user README），短信服务 P1。
 */
class AuthViewModel(
    private val api: Api = ServiceLocator.api,
    private val session: SessionStore = ServiceLocator.session,
) : MviViewModel<AuthUiState, AuthIntent, AuthEffect>(AuthUiState()) {

    override fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.SwitchMode -> setState {
                copy(mode = intent.mode, error = "", message = "", messageId = messageId + 1)
            }
            is AuthIntent.UsernameChanged -> setState { copy(username = intent.value, error = "") }
            is AuthIntent.CodeChanged -> setState { copy(verifyCode = intent.value, error = "") }
            is AuthIntent.PasswordChanged -> setState { copy(password = intent.value, error = "") }
            AuthIntent.Submit -> submit()
        }
    }

    private fun submit() {
        val s = currentState
        // 前置校验（参数类错误服务端 1001，本地先拦截给即时反馈）
        if (s.username.isBlank()) return setState { copy(error = "请输入手机号/用户名") }
        if (s.password.isBlank()) return setState { copy(error = "请输入密码") }
        if (s.mode == AuthMode.Register && s.verifyCode.isBlank()) {
            return setState { copy(error = "请输入验证码") }
        }
        setState { copy(loading = true, error = "") }

        viewModelScope.launch {
            try {
                val token = if (s.mode == AuthMode.Login) {
                    safeCall { api.login(LoginRequest(s.username.trim(), s.password)) }
                } else {
                    safeCall {
                        api.register(RegisterRequest(s.username.trim(), s.verifyCode.trim(), s.password))
                    }
                }
                // 落地会话（Token 不进日志）
                session.accessToken = token.accessToken
                session.refreshToken = token.refreshToken
                token.userId?.let { session.userId = it }
                session.nickname = token.nickname ?: token.username
                setState { copy(loading = false) }
                sendEffect(AuthEffect.LoggedIn)
            } catch (e: Throwable) {
                setState {
                    copy(
                        loading = false,
                        error = e.toApiMessage(),
                        message = e.toApiMessage(),
                        messageId = messageId + 1,
                    )
                }
            }
        }
    }
}
