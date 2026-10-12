package com.easyshopping.android

import android.app.Application
import com.easyshopping.android.di.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
