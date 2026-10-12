package com.easyshopping.android.core.network

import android.content.Context
import androidx.core.content.edit

/**
 * 本地会话存储（P0 用 SharedPreferences，P1 迁 EncryptedSharedPreferences/Keystore，见
 * plans/android-frontend.md 3.5.2）。Token 不进日志、不入崩溃附件。
 */
class SessionStore(context: Context) {

    private val sp = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = sp.getString(KEY_ACCESS, null)
        set(value) = sp.edit { putString(KEY_ACCESS, value) }

    var refreshToken: String?
        get() = sp.getString(KEY_REFRESH, null)
        set(value) = sp.edit { putString(KEY_REFRESH, value) }

    var userId: Long
        get() = sp.getLong(KEY_USER_ID, -1L)
        set(value) = sp.edit { putLong(KEY_USER_ID, value) }

    var nickname: String?
        get() = sp.getString(KEY_NICKNAME, null)
        set(value) = sp.edit { putString(KEY_NICKNAME, value) }

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

    /** 清空本地凭据（登出/刷新失败/被踢）。 */
    fun clear() = sp.edit {
        remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_USER_ID).remove(KEY_NICKNAME)
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_NICKNAME = "nickname"
    }
}
