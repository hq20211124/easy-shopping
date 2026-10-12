package com.easyshopping.android.feature.detail

import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.CartAddRequest
import com.easyshopping.android.core.network.ProductDetail
import com.easyshopping.android.core.network.SkuView
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.launch

data class ProductDetailUiState(
    val loading: Boolean = false,
    val detail: ProductDetail? = null,
    /** 规格名 → 已选值（例：{"颜色":"红","容量":"128G"}），未选的规格不出现在 map 里 */
    val selectedSpecs: Map<String, String> = emptyMap(),
    /** 当前命中的 SKU；所有规格选齐且组合存在时非 null */
    val matchedSku: SkuView? = null,
    /** 规格值可用性：key = "规格名|值"，value = 在当前已选条件下是否可选 */
    val valueEnabled: Map<String, Boolean> = emptyMap(),
    val quantity: Int = 1,
    val adding: Boolean = false,
    val added: Boolean = false,
    val error: String = "",
    /** 弹层与提示的自增 id，UI 侧只在变化时响应一次 */
    val messageId: Int = 0,
)

sealed interface ProductDetailIntent {
    data class SelectSpec(val name: String, val value: String) : ProductDetailIntent
    data class ChangeQuantity(val delta: Int) : ProductDetailIntent
    data object AddToCart : ProductDetailIntent
    data object Reload : ProductDetailIntent
}

sealed interface ProductDetailEffect {
    data class Added(val totalQuantity: Int) : ProductDetailEffect
    data object RequireLogin : ProductDetailEffect
}

/**
 * 商品详情 + 多规格 SKU 矩阵（plans/android-frontend.md 4.4）。
 *
 * SKU 匹配规则（唯一真源：ProductDetail.skus[].specs 的 name→value 映射）：
 *  - 已选集合是某个 sku.specs 的子集 → 命中该 sku（取价格/库存/图）
 *  - 某规格值可选 ⇔ 存在一个 sku 同时满足「该值 + 其余已选值」
 *  - 选新值时覆盖同名规格的旧值；若组合不存在则该 sku 命中为 null，价格回退 SPU 价
 */
class ProductDetailViewModel(
    private val spuId: Long,
    private val api: Api = ServiceLocator.api,
) : MviViewModel<ProductDetailUiState, ProductDetailIntent, ProductDetailEffect>(ProductDetailUiState()) {

    init {
        load()
    }

    override fun onIntent(intent: ProductDetailIntent) {
        when (intent) {
            is ProductDetailIntent.SelectSpec -> selectSpec(intent.name, intent.value)
            is ProductDetailIntent.ChangeQuantity -> changeQuantity(intent.delta)
            ProductDetailIntent.AddToCart -> addToCart()
            ProductDetailIntent.Reload -> load()
        }
    }

    private fun load() {
        setState { copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val d = safeCall { api.productDetail(spuId) }
                setState {
                    copy(
                        loading = false,
                        detail = d,
                        selectedSpecs = emptyMap(),
                        matchedSku = null,
                        valueEnabled = computeEnabled(emptyMap(), d),
                        quantity = 1,
                    )
                }
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
            }
        }
    }

    private fun selectSpec(name: String, value: String) {
        val d = currentState.detail ?: return
        // 再次点击已选值 = 取消选择（便于用户换一条路线）
        val next = if (currentState.selectedSpecs[name] == value) {
            currentState.selectedSpecs - name
        } else {
            currentState.selectedSpecs + (name to value)
        }
        setState {
            copy(
                selectedSpecs = next,
                matchedSku = findMatch(next, d),
                valueEnabled = computeEnabled(next, d),
            )
        }
    }

    private fun changeQuantity(delta: Int) {
        // 客户端只做步进（min=1）；库存/上限由服务端在下单与加购时校验
        // （core-product 买家视图不返回 SKU 库存，见 ProductDetail.toBuyerMap）
        setState { copy(quantity = (quantity + delta).coerceAtLeast(1)) }
    }

    private fun addToCart() {
        val s = currentState
        val sku = s.matchedSku
        if (sku == null) {
            setState { copy(error = "请选择完整规格", messageId = messageId + 1) }
            return
        }
        if (!ServiceLocator.session.isLoggedIn) {
            sendEffect(ProductDetailEffect.RequireLogin)
            return
        }
        setState { copy(adding = true, error = "") }
        viewModelScope.launch {
            try {
                val resp = safeCall { api.cartAdd(CartAddRequest(skuId = sku.skuId, quantity = s.quantity)) }
                setState { copy(adding = false, added = true) }
                sendEffect(ProductDetailEffect.Added(resp.cartTotalQuantity))
            } catch (e: Throwable) {
                // 3002 库存不足等业务错误：直接展示服务端 msg
                setState { copy(adding = false, error = e.toApiMessage(), messageId = messageId + 1) }
            }
        }
    }

    companion object {
        /** 命中 SKU：sku.specs 完整包含已选集合（未选齐时不命中，价格回退 SPU） */
        fun findMatch(selected: Map<String, String>, d: ProductDetail): SkuView? {
            if (selected.isEmpty()) return null
            return d.skus.firstOrNull { sku ->
                selected.all { (k, v) -> sku.specs[k] == v }
            }
        }

        /**
         * 规格值可用性：对每个未选/已选值，判断是否存在 sku 同时满足
         * 「该值 + 其余已选规格值」。已选值自身恒为可用（保证用户能取消）。
         */
        fun computeEnabled(selected: Map<String, String>, d: ProductDetail): Map<String, Boolean> {
            val map = mutableMapOf<String, Boolean>()
            d.specs.forEach { group ->
                group.values.forEach { value ->
                    val probe = selected - group.name + (group.name to value)
                    map[key(group.name, value)] = d.skus.any { sku ->
                        probe.all { (k, v) -> sku.specs[k] == v }
                    }
                }
            }
            return map
        }

        fun key(name: String, value: String) = "$name|$value"
    }
}
