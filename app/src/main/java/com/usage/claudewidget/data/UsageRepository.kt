package com.usage.claudewidget.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    data class Success(val snapshot: OpenCodeSnapshot) : FetchResult
    /** Key missing or rejected by the API; user must (re)enter it. */
    data object NeedsLogin : FetchResult
    /** Transient error (no network, rate limit, server error). Keep last snapshot. */
    data class Soft(val reason: String) : FetchResult
}

class UsageRepository(private val context: Context) {

    private val store = UsageStore.get(context)
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun refresh(): FetchResult = withContext(Dispatchers.IO) {
        val key = store.apiKey()
        if (key.isNullOrBlank()) {
            store.setAuthState(AuthState.NEEDS_LOGIN)
            return@withContext FetchResult.NeedsLogin
        }

        val req = Request.Builder()
            .url(Const.USAGE_URL)
            .header("Authorization", "Bearer $key")
            .get()
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val snapshot = OpenCodeSnapshot.parse(resp.body?.string().orEmpty(), System.currentTimeMillis())
                        if (store.hasKey()) {
                            store.saveSnapshot(snapshot)
                            store.setAuthState(AuthState.OK)
                            store.setLastError(null)
                        }
                        FetchResult.Success(snapshot)
                    }
                    401, 403 -> {
                        store.setAuthState(AuthState.NEEDS_LOGIN)
                        store.setLastError("Key rejected (HTTP ${resp.code})")
                        FetchResult.NeedsLogin
                    }
                    429 -> {
                        store.setLastError("Rate limited")
                        FetchResult.Soft("rate-limit")
                    }
                    else -> {
                        store.setLastError("HTTP ${resp.code}")
                        FetchResult.Soft("http-${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            val reason = e.message ?: "network error"
            store.setLastError(reason)
            FetchResult.Soft(reason)
        }
    }
}
