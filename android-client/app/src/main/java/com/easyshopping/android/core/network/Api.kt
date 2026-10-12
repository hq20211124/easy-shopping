package com.easyshopping.android.core.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 客户端侧 API 常量与 Retrofit 接口。**路径唯一真源**：server 各服务 README.md 端点清单。
 * 禁止在 feature 居字符串字面量拼路径（plans/android-frontend.md 1.6.1）。
 *
 * 注意：`core-*` 是**后端服务名**，与安卓客户端模块名 `core-network/core-ui` 同名不同物。
 */
interface Api {

    // ---------------------------------------------------------------- 认证 core-user
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): ApiResponse<TokenResponse>

    /** P0 验证码固定 123456（core-user README；P1 接短信服务）。 */
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): ApiResponse<TokenResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): ApiResponse<TokenResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequest): ApiResponse<Map<String, Boolean>>

    @GET("api/v1/user/profile")
    suspend fun profile(): ApiResponse<UserProfile>

    // ---------------------------------------------------------------- 商品 core-product
    /** 商品列表分页（首页/分类共用；sort: default/price_asc/price_desc/sales/newest）。 */
    @GET("api/v1/products")
    suspend fun products(
        @Query("categoryId") categoryId: Long? = null,
        @Query("keyword") keyword: String? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): ApiResponse<PageResult<ProductCard>>

    @GET("api/v1/products/{spuId}")
    suspend fun productDetail(@Path("spuId") spuId: Long): ApiResponse<ProductDetail>

    // ---------------------------------------------------------------- 搜索 core-search
    /** 关键词搜索（cursor 分页：首页不传 cursor，翻页传上一页 nextCursor）。 */
    @GET("api/v1/search/products")
    suspend fun search(
        @Query("q") q: String,
        @Query("sort") sort: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("pageSize") pageSize: Int = 20,
    ): ApiResponse<SearchResponse>

    @GET("api/v1/search/suggestions")
    suspend fun suggestions(@Query("q") q: String, @Query("limit") limit: Int = 10): ApiResponse<SuggestionResponse>

    @GET("api/v1/search/hot")
    suspend fun hotWords(@Query("limit") limit: Int = 10): ApiResponse<List<HotWord>>

    // ---------------------------------------------------------------- 购物车 core-cart
    @GET("api/v1/cart/list")
    suspend fun cartList(): ApiResponse<CartListResponse>

    @POST("api/v1/cart/add")
    suspend fun cartAdd(@Body body: CartAddRequest): ApiResponse<CartAddResponse>

    @POST("api/v1/cart/update")
    suspend fun cartUpdate(@Body body: CartUpdateRequest): ApiResponse<CartOpResponse>

    @POST("api/v1/cart/remove")
    suspend fun cartRemove(@Body body: CartRemoveRequest): ApiResponse<CartOpResponse>

    @POST("api/v1/cart/select")
    suspend fun cartSelect(@Body body: CartSelectRequest): ApiResponse<CartOpResponse>

    // ---------------------------------------------------------------- 地址 core-user
    @GET("api/v1/user/addresses")
    suspend fun addresses(): ApiResponse<AddressListResponse>

    @POST("api/v1/user/addresses")
    suspend fun createAddress(@Body body: AddressRequest): ApiResponse<AddressIdResponse>

    @PUT("api/v1/user/addresses/{addressId}")
    suspend fun updateAddress(@Path("addressId") addressId: Long, @Body body: AddressRequest): ApiResponse<AddressIdResponse>

    @DELETE("api/v1/user/addresses/{addressId}")
    suspend fun deleteAddress(@Path("addressId") addressId: Long): ApiResponse<AddressIdResponse>

    @POST("api/v1/user/addresses/{addressId}/default")
    suspend fun setDefaultAddress(@Path("addressId") addressId: Long): ApiResponse<AddressIdResponse>

    // ---------------------------------------------------------------- 订单 core-order
    /**
     * 下单：Idempotency-Key 由调用方生成（UUID），**必须带**，服务端按此键去重防重复下单。
     * 金额不随请求提交，由 core-price 实时计价（plans/android-backend.md 4.1）。
     */
    @POST("api/v1/orders")
    suspend fun createOrder(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateOrderRequest,
    ): ApiResponse<CreateOrderResponse>

    @GET("api/v1/orders")
    suspend fun orders(
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): ApiResponse<PageResult<OrderListVO>>

    @GET("api/v1/orders/{orderId}")
    suspend fun orderDetail(@Path("orderId") orderId: Long): ApiResponse<OrderDetailVO>

    @POST("api/v1/orders/{orderId}/cancel")
    suspend fun cancelOrder(@Path("orderId") orderId: Long, @Body body: Map<String, String?> = emptyMap()): ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>

    @POST("api/v1/orders/{orderId}/confirm")
    suspend fun confirmOrder(@Path("orderId") orderId: Long): ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>

    // ---------------------------------------------------------------- 支付 core-payment
    @POST("api/v1/payment/prepay")
    suspend fun prepay(@Body body: PrepayRequest): ApiResponse<PrepayResponse>

    /** P0 mock 付款：走与真实回调完全相同的处理链（core-payment README）。 */
    @POST("api/v1/payment/mock/pay")
    suspend fun mockPay(@Body body: MockPayRequest): ApiResponse<MockPayResponse>

    @GET("api/v1/payment/status/{orderId}")
    suspend fun paymentStatus(@Path("orderId") orderId: Long): ApiResponse<PaymentStatusResponse>

    // ---------------------------------------------------------------- 优惠券 core-promotion（结算降级用）
    @GET("api/v1/coupons/my")
    suspend fun myCoupons(@Query("status") status: Int? = 0): ApiResponse<List<CouponRecord>>
}
