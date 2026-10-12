package com.easyshopping.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.easyshopping.android.core.network.SessionEvents
import com.easyshopping.android.core.ui.theme.AppTheme
import com.easyshopping.android.di.ServiceLocator
import com.easyshopping.android.navigation.AppNavGraph
import kotlinx.coroutines.flow.collectLatest

/**
 * 单 Activity 架构：所有页面走 Compose Navigation（plans/android-frontend.md 1.4）
 *
 * 启动分流：已登录 → main（底部 Tab），未登录 → login；
 * 登录态失效（刷新失败 / 服务端踢下线）统一回 login。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val session = remember { ServiceLocator.session }
                val snackbarHostState = remember { SnackbarHostState() }
                val startDestination = if (session.isLoggedIn) "tab_home" else "login"

                // 全局会话事件：被踢下线时用 Snackbar 提示（UI 层只负责提示，路由由 NavGraph 控制）
                LaunchedEffect(Unit) {
                    SessionEvents.events.collectLatest { e ->
                        when (e) {
                            is SessionEvents.Event.KickedOut ->
                                snackbarHostState.showSnackbar(e.reason)
                        }
                    }
                }

                AppNavGraph(
                    startDestination = startDestination,
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}
