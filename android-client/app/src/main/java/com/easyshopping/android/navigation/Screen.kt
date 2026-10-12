package com.easyshopping.android.navigation

import android.net.Uri

/**
 * 路由表（P0 单 app module；P1 拆 modules/core/core-navigation 时整包迁移）。
 * 规则（plans/android-frontend.md 1.4.1）：① 入参一律 Uri.encode；② ID 用 Long 与后端 BIGINT 对齐；
 * ③ 深链参数只走 createRoute()，禁止字符串拼接。
 */
sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Main : Screen("main")                 // 底部 Tab 容器（首页/购物车/我的）
    data object Search : Screen("search")
    data object SearchResult : Screen("search/result/{keyword}") {
        fun createRoute(keyword: String) = "search/result/${Uri.encode(keyword)}"
    }
    data object ProductDetail : Screen("product/{spuId}") {
        fun createRoute(spuId: Long) = "product/$spuId"
    }
    data object Checkout : Screen("checkout")
    data object Pay : Screen("pay/{orderId}") {
        fun createRoute(orderId: Long) = "pay/$orderId"
    }
    data object OrderList : Screen("order/list?tab={tab}") {
        /** tab: all | unpaid | paid | shipped | completed（映射到 0 基状态值，见 OrderStatus） */
        fun createRoute(tab: String = "all") = "order/list?tab=$tab"
    }
    data object OrderDetail : Screen("order/{orderId}") {
        fun createRoute(orderId: Long) = "order/$orderId"
    }
    data object AddressList : Screen("address/list")
    data object AddressEdit : Screen("address/edit?addressId={addressId}") {
        fun createRoute(addressId: Long? = null) =
            addressId?.let { "address/edit?addressId=$it" } ?: "address/edit"
    }
}
