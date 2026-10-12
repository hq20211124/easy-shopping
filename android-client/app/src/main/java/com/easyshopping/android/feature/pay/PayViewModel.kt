package com.easyshopping.android.feature.pay

import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.MockPayRequest
import com.easyshopping.android.core.network.OrderDetailVO
import com.easyshopping.android.core.network.PrepayRequest
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PayUiState(
    val loading: Boolean = false,
    val order: OrderDetailVO? = null,
    /** 当前选择的支付渠道：1支付宝/2微信/3银行卡/4余额（core-payment） */
    val channel: Int = 1,
    val paymentId: Long? = null,
    val paying: Boolean = false,
    /** 服务端支付状态原文：pending/paid/refunding/refunded（禁用数字推断） */
    val paymentStatus: String? = null,
    val paid: Boolean = false,
    val polling: Boolean = false,
    val error: String = "",
    val messageId: Int = 0,
)

sealed interface PayIntent {
    data object Load : PayIntent
    data class SelectChannel(val channel: Int) : PayIntent
    /** 发起支付：prepay 拿 paymentId，再走 mock 支付（P0 无三方 SDK） */
    data object Pay : PayIntent
    data object StopPolling : PayIntent
}

sealed interface PayEffect {
    data class Paid(val orderId: Long) : PayEffect
    data class Toast(val message: String) : PayEffect
}

/**
 * 收银台（plans/android-frontend.md 4.6）。
 *
 * 关键口径（core-payment README）：
 *  - 支付结果**以服务端轮询为准**，客户端跳转状态不可信
 *  - `POST /api/v1/payment/prepay {orderId, channel}` → paymentId，channel ∈ 1..4
 *  - P0 走 `POST /api/v1/payment/mock/pay {paymentId}`（与真实回调同一条处理链）
 *  - 轮询 `GET /api/v1/payment/status/{orderId}`，指数退避，paid 后停止
 *  - 支付超时**不硬编码分钟数**，读订单的 payDeadline 倒计时展示
 */
class PayViewModel(
    private val orderId: Long,
    private val api: Api = ServiceLocator.api,
) : MviViewModel<PayUiState, PayIntent, PayEffect>(PayUiState()) {

    private var pollJob: Job? = null

    init {
        load()
    }

    override fun onIntent(intent: PayIntent) {
        when (intent) {
            PayIntent.Load -> load()
            is PayIntent.SelectChannel -> setState { copy(channel = intent.channel) }
            PayIntent.Pay -> pay()
            PayIntent.StopPolling -> pollJob?.cancel()
        }
    }

    private fun load() {
        setState { copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val d = safeCall { api.orderDetail(orderId) }
                // 已支付的订单直接展示成功，不再出现支付按钮
                setState {
                    copy(
                        loading = false,
                        order = d,
                        paid = d.status != 0,   // 0=待付款（OrderStatus.UNPAID）
                    )
                }
                if (currentState.paid) startPolling()
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
            }
        }
    }

    private fun pay() {
        val channel = currentState.channel
        setState { copy(paying = true, error = "") }
        viewModelScope.launch {
            try {
                // 1) prepay 换 paymentId（渠道在这里选定）
                val prepay = safeCall { api.prepay(PrepayRequest(orderId = orderId, channel = channel)) }
                setState { copy(paymentId = prepay.paymentId) }
                // 2) mock 支付：与真实回调共用 core-payment 处理链
                safeCall { api.mockPay(MockPayRequest(prepay.paymentId)) }
                setState { copy(paying = false) }
                startPolling()
            } catch (e: Throwable) {
                setState { copy(paying = false, error = e.toApiMessage(), messageId = messageId + 1) }
                sendEffect(PayEffect.Toast(e.toApiMessage()))
            }
        }
    }

    /**
     * 轮询支付状态：5 次快速（1s/2s/4s/8s/16s）后转慢轮询（30s），最多 3 分钟。
     * paid 立即停止并广播成功；超时后停止并提示"可稍后在订单中查看"。
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            setState { copy(polling = true) }
            val backoff = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
            var attempt = 0
            var waited = 0L
            while (waited <= MAX_WAIT_MS) {
                delay(if (attempt < backoff.size) backoff[attempt] else SLOW_INTERVAL_MS)
                waited += if (attempt < backoff.size) backoff[attempt] else SLOW_INTERVAL_MS
                attempt++
                val status = runCatching {
                    safeCall { api.paymentStatus(orderId) }.status
                }.getOrNull() ?: continue
                setState { copy(paymentStatus = status) }
                if (status == STATUS_PAID) {
                    setState { copy(polling = false, paid = true) }
                    sendEffect(PayEffect.Paid(orderId))
                    return@launch
                }
                // pending / refunding / refunded 继续等；refunded 不应出现在本页
            }
            setState { copy(polling = false) }
            sendEffect(PayEffect.Toast("支付结果确认超时，请稍后在订单列表查看"))
        }
    }

    companion object {
        private const val STATUS_PAID = "paid"
        private const val SLOW_INTERVAL_MS = 30_000L
        private const val MAX_WAIT_MS = 180_000L
    }
}
