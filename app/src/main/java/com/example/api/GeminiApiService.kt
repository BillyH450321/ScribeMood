package com.example.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RetryStatus(
    val attempt: Int,
    val maxRetries: Int,
    val delayMs: Long,
    val isRetrying: Boolean
)

object ApiRetryMonitor {
    private val _retryStatus = MutableStateFlow<RetryStatus?>(null)
    val retryStatus: StateFlow<RetryStatus?> = _retryStatus.asStateFlow()

    fun updateStatus(status: RetryStatus?) {
        _retryStatus.value = status
    }
}

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    @Json(name = "role") val role: String? = null,
    @Json(name = "parts") val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @Json(name = "responseModalities") val responseModalities: List<String>? = null,
    @Json(name = "speechConfig") val speechConfig: SpeechConfig? = null,
    @Json(name = "temperature") val temperature: Float? = null
)

@JsonClass(generateAdapter = true)
data class SpeechConfig(
    @Json(name = "voiceConfig") val voiceConfig: VoiceConfig
)

@JsonClass(generateAdapter = true)
data class VoiceConfig(
    @Json(name = "prebuiltVoiceConfig") val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@JsonClass(generateAdapter = true)
data class PrebuiltVoiceConfig(
    @Json(name = "voiceName") val voiceName: String
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    @Json(name = "candidates") val candidates: List<Candidate>?
)

@JsonClass(generateAdapter = true)
data class Candidate(
    @Json(name = "content") val content: Content?
)

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

class RetryInterceptor(
    private val maxRetries: Int = 6,
    private val initialDelayMs: Long = 1500L,
    private val backoffMultiplier: Double = 2.0
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var tryCount = 0
        var delayMs = initialDelayMs

        try {
            while (response.code == 429 && tryCount < maxRetries) {
                tryCount++
                
                // Check for Retry-After header which some APIs provide
                val retryAfterHeader = response.header("Retry-After")
                var backoffDelay = if (retryAfterHeader != null) {
                    val seconds = retryAfterHeader.toLongOrNull()
                    if (seconds != null) seconds * 1000L else delayMs
                } else {
                    delayMs
                }
                
                // Add a randomized jitter between 0 and 500 milliseconds to avoid collision
                val jitter = (Math.random() * 500).toLong()
                val totalDelay = backoffDelay + jitter
                
                Log.w("RetryInterceptor", "Received 429 Rate Limit. Retrying request (attempt $tryCount/$maxRetries) in ${totalDelay}ms...")
                
                ApiRetryMonitor.updateStatus(
                    RetryStatus(
                        attempt = tryCount,
                        maxRetries = maxRetries,
                        delayMs = totalDelay,
                        isRetrying = true
                    )
                )

                response.close()
                try {
                    Thread.sleep(totalDelay)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Retry interrupted", e)
                }
                delayMs = (delayMs * backoffMultiplier).toLong()
                response = chain.proceed(request)
            }
        } finally {
            ApiRetryMonitor.updateStatus(null)
        }
        return response
    }
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor())
        .build()

    val service: GeminiApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
        retrofit.create(GeminiApiService::class.java)
    }
}
