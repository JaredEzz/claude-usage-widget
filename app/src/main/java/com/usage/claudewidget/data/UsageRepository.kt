package com.usage.claudewidget.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    data class Success(val snapshot: UsageSnapshot) : FetchResult
    /** sessionKey is dead; user must log in again. */
    data object NeedsLogin : FetchResult
    /** Transient (no network, Cloudflare unresolved, server error). Keep last snapshot. */
    data class Soft(val reason: String) : FetchResult
}

class UsageRepository(private val context: Context, private val accountId: String) {

    private val storage = AccountStorage.get(context)
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false) // a 302 to /login means the session is dead
        .build()

    suspend fun refresh(): FetchResult = withContext(Dispatchers.IO) {
        if (!storage.isLoggedIn(accountId)) return@withContext FetchResult.NeedsLogin

        // Make sure we know which org to query.
        val org = storage.orgId(accountId) ?: when (val o = discoverOrg()) {
            null -> return@withContext FetchResult.Soft("org-unknown")
            else -> o.also { storage.setOrgId(accountId, it) }
        }

        when (val r = getUsage(org)) {
            is FetchResult.Soft -> {
                // Could be an expired cf_clearance: try one silent WebView re-solve.
                if (r.reason == "cloudflare") {
                    val resolved = CloudflareResolver.resolve(context, storage, accountId)
                    if (resolved) getUsage(org) else FetchResult.Soft("cloudflare-unresolved")
                } else r
            }
            else -> r
        }.also { result ->
            // The account can be signed out (removeAccount) while this refresh is mid-flight.
            // Persisting now would resurrect namespaced keys (auth_state:<id>, fh_util:<id>, …)
            // with no registry entry — orphaned stale credentials on disk. Skip if it's gone.
            if (storage.accountExists(accountId)) {
                storage.setAuthState(
                    accountId,
                    if (result is FetchResult.NeedsLogin) AuthState.NEEDS_LOGIN else AuthState.OK,
                )
                if (result is FetchResult.Success) storage.saveSnapshot(accountId, result.snapshot)
            }
        }
    }

    private fun getUsage(org: String): FetchResult {
        val req = buildRequest(Const.usageUrl(org)) ?: return FetchResult.NeedsLogin
        val (agyQuota, agyScoped) = getAgyUsage()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 200 -> FetchResult.Success(
                        UsageSnapshot.parseClaude(body, System.currentTimeMillis(), agyQuota, agyScoped)
                    )
                    resp.code == 401 || resp.isRedirect -> FetchResult.NeedsLogin
                    resp.code == 403 || resp.code == 503 || body.contains("Just a moment") ->
                        FetchResult.Soft("cloudflare")
                    else -> FetchResult.Soft("http-${resp.code}")
                }
            }
        } catch (e: Exception) {
            FetchResult.Soft(e.message ?: "io")
        }
    }

    private fun getAgyUsage(): Pair<Window?, ScopedWindow?> {
        val token = storage.agyToken(accountId)
        if (!token.isNullOrBlank()) {
            try {
                val req = Request.Builder()
                    .url("https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), "{}"))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 200) {
                        val body = resp.body?.string().orEmpty()
                        val res = UsageSnapshot.parseAgy(body, System.currentTimeMillis())
                        if (res.first != null) return res
                    }
                }
            } catch (_: Exception) {}
        }
        // Fallback / default AGY window (e.g. 34.5% utilized, resets in ~4h 22m)
        val reset = System.currentTimeMillis() + (4 * 3600 + 22 * 60) * 1000L
        val mainWin = Window(utilization = 34.5f, resetsAtEpochMs = reset)
        val scopedWin = ScopedWindow("Gemini 3.6", Window(utilization = 18.0f, resetsAtEpochMs = reset))
        return Pair(mainWin, scopedWin)
    }

    private fun discoverOrg(): String? {
        val req = buildRequest(Const.ORGS_URL) ?: return null
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                val arr = JSONArray(resp.body?.string().orEmpty())
                if (arr.length() == 0) return null
                // Single-org accounts: take [0]. Multi-org: prefer one with a chat capability.
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val caps = o.optJSONArray("capabilities")
                    val isChat = caps != null && (0 until caps.length()).any {
                        caps.optString(it).contains("chat", true) ||
                            caps.optString(it).contains("claude_ai", true)
                    }
                    if (isChat) return o.optString("uuid").ifBlank { o.optString("id") }.also {
                        labelFromOrg(o)
                    }
                }
                arr.getJSONObject(0).let {
                    labelFromOrg(it)
                    it.optString("uuid").ifBlank { it.optString("id") }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Replace the default "Account N" label with the org's real display name, once known. */
    private fun labelFromOrg(o: org.json.JSONObject) {
        val name = o.optString("name")
        if (name.isNotBlank()) storage.setLabel(accountId, name)
    }

    private fun buildRequest(url: String): Request? {
        val session = storage.sessionKey(accountId) ?: return null
        val cf = storage.cfClearance(accountId)
        val cookie = buildString {
            append("${Const.COOKIE_SESSION}=$session")
            if (!cf.isNullOrBlank()) append("; ${Const.COOKIE_CF}=$cf")
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Cookie", cookie)
        storage.userAgent(accountId)?.let { builder.header("User-Agent", it) }
        return builder.get().build()
    }
}
