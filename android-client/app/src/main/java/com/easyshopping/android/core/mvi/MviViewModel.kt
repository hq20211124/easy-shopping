package com.easyshopping.android.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.network.ApiException
import com.easyshopping.android.core.network.ErrorCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI 基座（plans/android-frontend.md 1.1 约定）：
 * 单一 UiState（不可变 data class）+ Intent 进 / State 出 + Effect 走 Channel 不进 State。
 * 禁止在 Composable 内直接改状态。
 */
abstract class MviViewModel<S : Any, I : Any, E : Any>(initial: S) : ViewModel() {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = Channel<E>(Channel.BUFFERED)
    val effect: Flow<E> = _effect.receiveAsFlow()

    protected val currentState: S get() = _state.value

    protected fun setState(reducer: S.() -> S) {
        _state.update { it.reducer() }
    }

    protected fun sendEffect(effect: E) {
        _effect.trySend(effect)
    }

    /** 统一入口：Intent → 状态迁移。子类只关心业务，不重复 try/catch。 */
    abstract fun onIntent(intent: I)

    /**
     * 统一异步错误兜底：ApiException → 回调（子类一般写 state.error = msg 或发 Effect 提示）。
     * 返回 true 表示已被处理。
     */
    protected fun Throwable.toApiMessage(): String =
        (this as? ApiException)?.message ?: "网络异常，请稍后重试"

    protected fun isReloginError(e: Throwable): Boolean =
        e is ApiException && ErrorCode.needRelogin(e.code)
}
