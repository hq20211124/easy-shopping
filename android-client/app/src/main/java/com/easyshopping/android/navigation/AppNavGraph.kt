package com.easyshopping.android.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.easyshopping.android.feature.auth.AuthScreen
import com.easyshopping.android.feature.cart.CartScreen
import com.easyshopping.android.feature.checkout.CheckoutScreen
import com.easyshopping.android.feature.home.HomeScreen
import com.easyshopping.android.feature.order.OrderDetailScreen
import com.easyshopping.android.feature.order.OrderListScreen
import com.easyshopping.android.feature.pay.PayScreen
import com.easyshopping.android.feature.search.SearchScreen
import com.easyshopping.android.feature.search.SearchResultScreen
import com.easyshopping.android.feature.user.AddressEditScreen
import com.easyshopping.android.feature.user.AddressListScreen
import com.easyshopping.android.feature.user.UserScreen
import com.easyshopping.android.feature.detail.ProductDetailScreen

/**
 * 底部 Tab（P0 三个：首页/购物车/我的；订单从「我的」与结算完成页进入）
 * Tab 路由必须与 Screen 常量一致，避免字符串漂移。
 */
private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab("tab_home", "首页", Icons.Filled.Home),
    BottomTab("tab_cart", "购物车", Icons.Filled.ShoppingCart),
    BottomTab("tab_user", "我的", Icons.Filled.Person),
)

/**
 * 全局导航图（P0 单 app module；P1 拆 modules/feature-* 后迁移到 core-navigation）
 *
 * 跳转约定（plans/android-frontend.md 1.4.2）：
 *  - 登录/注册 → main（登录成功后 clear 任务栈，返回键不回到登录页）
 *  - 下单成功 → pay/{orderId}，支付结果以轮询服务端为准（不信任本地跳转状态）
 *  - 被踢下线（SessionEvents）→ 全局回 login
 */
@Composable
fun AppNavGraph(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
    snackbarHostState: SnackbarHostState,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomTabs.any { it.route == currentRoute }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                // Tab 切换保持各自返回栈（standard + saveState/restoreState）
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable("tab_home") {
                    HomeScreen(
                        onOpenSearch = { navController.navigate(Screen.Search.route) },
                        onOpenSearchResult = { kw -> navController.navigate(Screen.SearchResult.createRoute(kw)) },
                        onOpenProduct = { spuId -> navController.navigate(Screen.ProductDetail.createRoute(spuId)) },
                    )
                }
                composable("tab_cart") {
                    CartScreen(
                        onOpenProduct = { spuId -> navController.navigate(Screen.ProductDetail.createRoute(spuId)) },
                        onCheckout = { navController.navigate(Screen.Checkout.route) },
                        onRequireLogin = { navController.navigate(Screen.Login.route) },
                    )
                }
                composable("tab_user") {
                    UserScreen(
                        onRequireLogin = { navController.navigate(Screen.Login.route) },
                        onOpenOrders = { tab -> navController.navigate(Screen.OrderList.createRoute(tab)) },
                        onOpenAddresses = { navController.navigate(Screen.AddressList.route) },
                    )
                }

                composable(Screen.Login.route) {
                    AuthScreen(
                        onLoggedIn = {
                            // 登录成功：清空任务栈再进主界面，返回键不回到登录页
                            navController.navigate("tab_home") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Register.route) {
                    AuthScreen(
                        initialRegister = true,
                        onLoggedIn = {
                            navController.navigate("tab_home") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Screen.Search.route) {
                    SearchScreen(
                        onSearch = { kw -> navController.navigate(Screen.SearchResult.createRoute(kw)) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.SearchResult.route,
                    arguments = listOf(navArgument("keyword") { type = NavType.StringType }),
                ) { entry ->
                    SearchResultScreen(
                        keyword = entry.arguments?.getString("keyword").orEmpty(),
                        onOpenProduct = { spuId -> navController.navigate(Screen.ProductDetail.createRoute(spuId)) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.ProductDetail.route,
                    arguments = listOf(navArgument("spuId") { type = NavType.LongType }),
                ) { entry ->
                    ProductDetailScreen(
                        spuId = entry.arguments?.getLong("spuId") ?: -1L,
                        onBack = { navController.popBackStack() },
                        onGoCart = {
                            navController.navigate("tab_cart") {
                                popUpTo(navController.graph.findStartDestination().id)
                            }
                        },
                        onRequireLogin = { navController.navigate(Screen.Login.route) },
                    )
                }

                composable(Screen.Checkout.route) {
                    CheckoutScreen(
                        onBack = { navController.popBackStack() },
                        onOrdered = { orderId ->
                            navController.navigate(Screen.Pay.createRoute(orderId)) {
                                popUpTo(Screen.Checkout.route) { inclusive = true }
                            }
                        },
                        onRequireLogin = { navController.navigate(Screen.Login.route) },
                    )
                }
                composable(
                    route = Screen.Pay.route,
                    arguments = listOf(navArgument("orderId") { type = NavType.LongType }),
                ) { entry ->
                    PayScreen(
                        orderId = entry.arguments?.getLong("orderId") ?: -1L,
                        onBack = { navController.popBackStack() },
                        onGoOrders = { tab ->
                            navController.navigate(Screen.OrderList.createRoute(tab)) {
                                popUpTo("tab_home")
                            }
                        },
                        onGoHome = {
                            navController.navigate("tab_home") { popUpTo("tab_home") { inclusive = true } }
                        },
                    )
                }

                composable(
                    route = Screen.OrderList.route,
                    arguments = listOf(navArgument("tab") {
                        type = NavType.StringType
                        defaultValue = "all"
                    }),
                ) { entry ->
                    OrderListScreen(
                        initialTab = entry.arguments?.getString("tab") ?: "all",
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { orderId -> navController.navigate(Screen.OrderDetail.createRoute(orderId)) },
                        onGoPay = { orderId -> navController.navigate(Screen.Pay.createRoute(orderId)) },
                    )
                }
                composable(
                    route = Screen.OrderDetail.route,
                    arguments = listOf(navArgument("orderId") { type = NavType.LongType }),
                ) { entry ->
                    val orderId = entry.arguments?.getLong("orderId") ?: -1L
                    OrderDetailScreen(
                        orderId = orderId,
                        onBack = { navController.popBackStack() },
                        onGoPay = { navController.navigate(Screen.Pay.createRoute(orderId)) },
                    )
                }

                composable(Screen.AddressList.route) {
                    AddressListScreen(
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate(Screen.AddressEdit.createRoute(id)) },
                        onCreate = { navController.navigate(Screen.AddressEdit.createRoute(null)) },
                    )
                }
                composable(
                    route = Screen.AddressEdit.route,
                    arguments = listOf(navArgument("addressId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    }),
                ) { entry ->
                    val id = entry.arguments?.getLong("addressId")?.takeIf { it > 0 }
                    AddressEditScreen(
                        addressId = id,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
