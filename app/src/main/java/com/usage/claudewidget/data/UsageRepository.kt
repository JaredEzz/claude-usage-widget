package com.usage.claudewidget.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    data class Success(val snapshot: AntigravitySnapshot) : FetchResult
    /** Session/token is dead; user must log in again. */
    data object NeedsLogin : FetchResult
    /** Transient error (no network, rate limit, server error). Keep last snapshot. */
    data class Soft(val reason: String) : FetchResult
}

class UsageRepository(private val context: Context, private val accountId: String) {

    private val storage = AccountStorage.get(context)
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun refresh(): FetchResult = withContext(Dispatchers.IO) {
        if (!storage.isLoggedIn(accountId)) return@withContext FetchResult.NeedsLogin

        val token = getValidAccessToken() ?: return@withContext FetchResult.NeedsLogin

        var projectId = storage.projectId(accountId)
        if (projectId.isNullOrBlank()) {
            projectId = resolveProjectId(token)
            if (!projectId.isNullOrBlank()) {
                storage.setProjectId(accountId, projectId)
            }
        }

        val result = fetchQuotaSummary(token, projectId)

        if (storage.accountExists(accountId)) {
            storage.setAuthState(
                accountId,
                if (result is FetchResult.NeedsLogin) AuthState.NEEDS_LOGIN else AuthState.OK,
            )
            if (result is FetchResult.Success) {
                storage.saveSnapshot(accountId, result.snapshot)
            }
        }
        result
    }

    private fun getValidAccessToken(): String? {
        val currentToken = storage.accessToken(accountId)
        val expiresAt = storage.tokenExpiresAt(accountId)
        val now = System.currentTimeMillis()

        // Reuse existing token if valid for at least another 60 seconds
        if (!currentToken.isNullOrBlank() && expiresAt > now + 60_000L) {
            return currentToken
        }

        val refreshToken = storage.refreshToken(accountId) ?: return null
        return refreshAccessToken(refreshToken)
    }

    private fun refreshAccessToken(refreshToken: String): String? {
        val form = FormBody.Builder()
            .add("client_id", Const.GOOGLE_CLIENT_ID)
            .add("client_secret", Const.GOOGLE_CLIENT_SECRET)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val req = Request.Builder()
            .url(Const.GOOGLE_TOKEN_URL)
            .post(form)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 200) {
                    val json = JSONObject(body)
                    val newAccessToken = json.optString("access_token")
                    val expiresInSec = json.optLong("expires_in", 3600L)
                    if (newAccessToken.isNotBlank()) {
                        storage.setAccessToken(accountId, newAccessToken)
                        storage.setTokenExpiresAt(accountId, System.currentTimeMillis() + expiresInSec * 1000L)
                        newAccessToken
                    } else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveProjectId(accessToken: String): String? {
        val metadata = JSONObject().apply {
            put("ideType", "ANTIGRAVITY")
            put("platform", "PLATFORM_UNSPECIFIED")
            put("pluginType", "GEMINI")
        }
        val bodyJson = JSONObject().apply {
            put("metadata", metadata)
        }

        val req = Request.Builder()
            .url(Const.LOAD_CODE_ASSIST_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", Const.USER_AGENT)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 200) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val companion = json.opt("cloudaicompanionProject")
                    when (companion) {
                        is String -> companion.takeIf { it.isNotBlank() }
                        is JSONObject -> companion.optString("id").takeIf { it.isNotBlank() }
                        else -> null
                    }
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchQuotaSummary(accessToken: String, projectId: String?): FetchResult {
        val bodyJson = JSONObject().apply {
            if (!projectId.isNullOrBlank()) {
                put("project", projectId)
            }
        }

        val req = Request.Builder()
            .url(Const.QUOTA_SUMMARY_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", Const.USER_AGENT)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> {
                        val snapshot = AntigravitySnapshot.parseQuotaSummary(body, System.currentTimeMillis())
                        FetchResult.Success(snapshot)
                    }
                    401, 403 -> {
                        // Attempt one force-refresh of token
                        val refreshToken = storage.refreshToken(accountId)
                        val refreshed = if (refreshToken != null) refreshAccessToken(refreshToken) else null
                        if (refreshed != null) {
                            fetchQuotaSummaryRetry(refreshed, projectId)
                        } else {
                            FetchResult.NeedsLogin
                        }
                    }
                    429 -> FetchResult.Soft("rate-limit")
                    else -> FetchResult.Soft("http-${resp.code}")
                }
            }
        } catch (e: Exception) {
            FetchResult.Soft(e.message ?: "io")
        }
    }

    private fun fetchQuotaSummaryRetry(accessToken: String, projectId: String?): FetchResult {
        val bodyJson = JSONObject().apply {
            if (!projectId.isNullOrBlank()) {
                put("project", projectId)
            }
        }
        val req = Request.Builder()
            .url(Const.QUOTA_SUMMARY_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", Const.USER_AGENT)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 200) {
                    val snapshot = AntigravitySnapshot.parseQuotaSummary(resp.body?.string().orEmpty(), System.currentTimeMillis())
                    FetchResult.Success(snapshot)
                } else if (resp.code == 401 || resp.code == 403) {
                    FetchResult.NeedsLogin
                } else {
                    FetchResult.Soft("http-${resp.code}")
                }
            }
        } catch (e: Exception) {
            FetchResult.Soft(e.message ?: "io")
        }
    }
}
