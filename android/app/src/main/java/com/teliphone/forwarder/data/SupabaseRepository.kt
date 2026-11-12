package com.teliphone.forwarder.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SupabaseRepository(private val context: Context) {
    private val prefs = AppPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun sendMessage(payload: MessagePayload): Result<Unit> = withContext(Dispatchers.IO) {
        if (!prefs.isConfigured) {
            val errorMsg = "Supabase не настроен (URL или Anon Key пусты)"
            Log.e(TAG, errorMsg)
            prefs.lastLog = "[Ошибка] $errorMsg"
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        val endpoint = "${prefs.supabaseUrl}/rest/v1/messages"
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toJson().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", prefs.supabaseAnonKey)
            .addHeader("Authorization", "Bearer ${prefs.supabaseAnonKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val log = "[Успех] Отправлено (${payload.source}): ${payload.sender}"
                    Log.d(TAG, log)
                    prefs.lastLog = log
                    Result.success(Unit)
                } else {
                    val errorBody = response.body?.string() ?: "Нет деталей"
                    val log = "[Ошибка ${response.code}] $errorBody"
                    Log.e(TAG, log)
                    prefs.lastLog = log
                    Result.failure(Exception("HTTP ${response.code}: $errorBody"))
                }
            }
        } catch (e: Exception) {
            val log = "[Сбой сети] ${e.localizedMessage ?: e.message}"
            Log.e(TAG, log, e)
            prefs.lastLog = log
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SupabaseRepo"
    }
}
