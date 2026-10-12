package com.easyshopping.android.feature.user

import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.UserProfile
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.launch

data class UserUiState(
    val loading: Boolean = false,
    val profile: UserProfile? = null,
    val nickname: String = "",
    val cartCount: Int = 0,
    val error: String = "",
    val needLogin: Boolean = false,
)

sealed interface UserIntent {
    data object Load : UserIntent
    data object Logout : UserIntent
}

sealed interface UserEffect {
    data object LoggedOut : UserEffect
    data class Toast(val message: String) : UserEffect
}

/**
 * 「我的」页（plans/android-frontend.md 4.9）：用户信息 + 订单/地址入口 + 退出登录。
 * 未登录时展示登录引导（不直接崩，也不隐藏入口）。
 */
class UserViewModel(
    private val api: Api = ServiceLocator.api,
    private val session: com.easyshopping.android.core.network.SessionStore = ServiceLocator.session,
) : MviViewModel<UserUiState, UserIntent, UserEffect>(UserUiState()) {

    init {
        if (!session.isLoggedIn) {
            setState { copy(needLogin = true) }
        } else {
            load()
        }
    }

    override fun onIntent(intent: UserIntent) {
        when (intent) {
            UserIntent.Load -> load()
            UserIntent.Logout -> logout()
        }
    }

    private fun load() {
        setState { copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val p = safeCall { api.profile() }
                setState {
                    copy(
                        loading = false,
                        profile = p,
                        nickname = p.nickname ?: p.username ?: session.nickname ?: "用户",
                    )
                }
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
                if (isReloginError(e)) setState { copy(needLogin = true) }
            }
        }
    }

    /** 退出登录：清本地凭证 + 尽力调 logout（失败也不阻塞本地登出） */
    private fun logout() {
        viewModelScope.launch {
            val refreshToken = session.refreshToken
            if (!refreshToken.isNullOrBlank()) {
                runCatching { safeCall { api.logout(com.easyshopping.android.core.network.RefreshRequest(refreshToken)) } }
            }
            session.clear()
            setState { copy(needLogin = true, profile = null, nickname = "") }
            sendEffect(UserEffect.LoggedOut)
        }
    }
}