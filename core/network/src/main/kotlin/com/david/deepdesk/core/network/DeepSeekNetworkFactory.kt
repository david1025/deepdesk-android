package com.david.deepdesk.core.network

import com.david.deepdesk.core.common.ApiKeyProvider
import com.david.deepdesk.core.model.FetchApiResponse
import com.david.deepdesk.core.model.FetchRequest
import com.david.deepdesk.core.model.ModelsResponse
import com.david.deepdesk.core.model.SearchApiResponse
import com.david.deepdesk.core.model.SearchRequest
import com.david.deepdesk.core.model.UserBalanceResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/** 普通 REST 端点（模型列表 / 独立 Search / Fetch） */
interface DeepSeekRestApi {
    @GET("models")
    suspend fun listModels(): ModelsResponse

    @GET("user/balance")
    suspend fun getBalance(): UserBalanceResponse

    @POST("v1/tools/search")
    suspend fun search(@Body body: SearchRequest): SearchApiResponse

    @POST("v1/tools/fetch")
    suspend fun fetch(@Body body: FetchRequest): FetchApiResponse
}

/** 统一 JSON 配置：宽松解析，未知字段忽略（协议演进安全） */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

object DeepSeekNetworkFactory {

    const val BASE_URL = "https://api.deepseek.com/"

    fun createOkHttpClient(keyProvider: ApiKeyProvider, debugLogging: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS) // SSE 思考期间可能长时间静默
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // 流式请求不设总超时，由 message_stop 终止
            .addInterceptor(AuthInterceptor(keyProvider))
            .addInterceptor(RedactedLoggingInterceptor())
        if (debugLogging) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }

    fun createRestApi(client: OkHttpClient): DeepSeekRestApi =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekRestApi::class.java)
}
