package com.easyshopping.android.di

import android.content.Context
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.NetworkClient
import com.easyshopping.android.core.network.SessionStore

/**
 * 手写依赖容器（P0 不引 Hilt，见任务约定；P1 拆多模块后再迁 Hilt/Sealed DI）。
 * 用法：`ServiceLocator.api`、`ServiceLocator.session`；ViewModel 不直接 new OkHttp。
 */
object ServiceLocator {

    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        NetworkClient.init(appContext)
    }

    val api: Api get() = NetworkClient.service
    val session: SessionStore get() = NetworkClient.session
}
