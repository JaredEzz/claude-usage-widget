package com.usage.claudewidget.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Silently re-mints a fresh cf_clearance cookie by loading claude.ai in an off-screen
 * WebView, which executes Cloudflare's JS challenge. Requires a still-valid sessionKey
 * cookie for [accountId] (no user interaction needed).
 *
 * Must touch the WebView only on the main thread; safe to call from a background coroutine.
 */
object CloudflareResolver {

    /** @return true if a cf_clearance cookie was obtained and harvested into [accountId]'s slot. */
    suspend fun resolve(
        context: Context,
        storage: AccountStorage,
        accountId: String,
        timeoutMs: Long = 30_000,
    ): Boolean {
        // The WebView cookie jar is a process-global shared app-wide, so wipe whatever the
        // previously-processed account left behind before seeding this account's session —
        // otherwise the challenge may reuse another account's cookies and harvest them into the
        // wrong slot. The whole clear→seed→challenge→harvest span must be serialized against every
        // other component that touches the jar (a concurrent tap-refresh, the periodic worker, or
        // an in-progress login), so hold the shared lock across all of it.
        return CookieJarLock.mutex.withLock {
            clearJar()
            seedSessionCookie(storage, accountId)

            val ok = withTimeoutOrNull(timeoutMs) {
                runWebViewChallenge(context.applicationContext, storage, accountId)
            } ?: false

            ok && !storage.cfClearance(accountId).isNullOrBlank()
        }
    }

    private fun clearJar() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    private fun seedSessionCookie(storage: AccountStorage, accountId: String) {
        val session = storage.sessionKey(accountId) ?: return
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie(Const.BASE, "${Const.COOKIE_SESSION}=$session; Domain=.claude.ai; Path=/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runWebViewChallenge(
        appCtx: Context,
        storage: AccountStorage,
        accountId: String,
    ): Boolean =
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            main.post {
                lateinit var webView: WebView
                var settled = false
                val cm = CookieManager.getInstance()

                fun finish(result: Boolean, view: WebView) {
                    if (settled) return
                    settled = true
                    cm.flush()
                    if (result) CookieHarvester.harvest(storage, accountId)
                    view.stopLoading()
                    view.destroy()
                    if (cont.isActive) cont.resume(result)
                }

                webView = WebView(appCtx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    storage.userAgent(accountId)?.let { settings.userAgentString = it }
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // Cloudflare may reload several times; check whether clearance landed.
                            val cookies = cm.getCookie(Const.BASE) ?: ""
                            if (cookies.contains("${Const.COOKIE_CF}=")) {
                                finish(true, view)
                            }
                            // else: keep waiting; challenge page will navigate again, or we time out.
                        }
                    }
                }
                webView.loadUrl(Const.CHALLENGE_URL)

                cont.invokeOnCancellation {
                    main.post { if (!settled) { settled = true; webView.destroy() } }
                }
            }
        }
}
