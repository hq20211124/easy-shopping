package com.easyshopping.android.core.network

import android.content.Context
import android.util.Log
import com.easyshopping.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网络层组装：OkHttp（签名→幂等→鉴权→日志 链）+ Retrofit + kotlinx.serialization。
 * 超时口径见 plans/android-frontend.md 1.6.5：连接 5s / 读 10s / 写 10s。
 */
object NetworkClient {

    @Volatile
    private var api: Api? = null

    lateinit var session: SessionStore
        private set

    fun init(context: Context) {
        if (api != null) return
        synchronized(this) {
            if (api != null) return
            session = SessionStore(context.applicationContext)
            api = buildApi()
        }
    }

    val service: Api
        get() = api ?: error("NetworkClient.init(context) 未调用（须在 App.onCreate 最早执行）")

    /** 独立刷新通道：不带鉴权头、不挂 Authenticator，避免 401 递归。 */
    private val refreshApi: Api by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(Api::class.java)
    }

    private val json = LenientJson.instance

    private fun buildApi(): Api {
        val logging = HttpLoggingInterceptor { msg -> Log.d("net", msg) }.apply {
            // 仅 debug 开启；BODY 级日志不含 Authorization（OkHttp 默认脱敏该头）
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // 顺序：CommonHeaders（幂等键+鉴权+签名）→ Log；401 单飞刷新挂 Authenticator
            .addInterceptor(CommonHeadersInterceptor(session))
            .addInterceptor(logging)
            // Authenticator 在 OkHttp 工作线程回调（非协程），此处 runBlocking 阻塞等待刷新结果
            .authenticator(
                TokenAuthenticator(session) { refreshToken ->
                    runBlocking {
                        runCatching { refreshApi.refresh(RefreshRequest(refreshToken)).data }.getOrNull()
                    }
                }
            )
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(Api::class.java)
    }
}

/**
 * 统一调用入口：把 HTTP/IO/业务码异常收敛为 [ApiException]，业务层只感知 code + message。
 *
 * - code != 0 → ApiException(code, msg)（1001/3002/4002/9999 语义见 [ErrorCode]）
 * - HTTP 401/403（网关拦截，不一定走业务码）→ 1002/1004
 * - 网络不可达 → 9999 + 可读文案
 */
suspend fun <T> safeCall(block: suspend () -> ApiResponse<T>): T = withContext(Dispatchers.IO) {
    try {
        val resp = block()
        if (!resp.isSuccess) throw ApiException(resp.code, resp.text)
        @Suppress("UNCHECKED_CAST")
        (resp.data as? T) ?: throw ApiException(ErrorCode.SYSTEM_ERROR, "响应数据为空")
    } catch (e: ApiException) {
        throw e
    } catch (e: HttpException) {
        val code = when (e.code()) {
            401 -> ErrorCode.NOT_LOGIN
            403 -> ErrorCode.NO_PERMISSION
            else -> ErrorCode.SYSTEM_ERROR
        }
        // 网关 401 响应体也是统一信封，尽量取出服务端 msg（如「账号在其他设备登录」）
        val serverMsg = runCatching {
            e.response()?.errorBody()?.string()?.let { body ->
                LenientJson.instance
                    .decodeFromString<ApiResponse<kotlinx.serialization.json.JsonElement>>(body).text
            }
        }.getOrNull()
        throw ApiException(code, serverMsg ?: "请求失败(${e.code()})", e.code())
    } catch (e: IOException) {
        throw ApiException(ErrorCode.SYSTEM_ERROR, "网络异常，请检查网络后重试")
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiException(ErrorCode.SYSTEM_ERROR, "数据解析失败，请升级客户端")
    }
}
