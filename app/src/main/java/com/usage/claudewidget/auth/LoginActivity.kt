package com.usage.claudewidget.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.usage.claudewidget.data.AccountStorage
import com.usage.claudewidget.data.Const
import com.usage.claudewidget.data.CookieHarvester
import com.usage.claudewidget.data.CookieJarLock
import com.usage.claudewidget.widget.UsageWidget
import com.usage.claudewidget.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * One-time interactive login. Loads claude.ai in a WebView; once a sessionKey cookie
 * appears, harvests cookies + the WebView's User-Agent into an account slot, then finishes.
 *
 * Pass [EXTRA_ACCOUNT_ID] to re-authenticate an existing account; omit it to create a new one.
 * Returns the (new or reused) accountId to the caller via [EXTRA_ACCOUNT_ID] in the result Intent.
 *
 * Pass [EXTRA_START_URL] to load a specific URL (e.g. an emailed magic-link) instead of the plain
 * login page — lets a magic link be completed inside this app's own WebView/cookie jar rather than
 * the device's default browser (whose cookies this app can never see). Not exported, so this is only
 * reachable from within the app or via `adb shell am start`, not by other apps on the device.
 */
class LoginActivity : Activity() {

    private lateinit var webView: WebView
    private val storage by lazy { AccountStorage.get(this) }
    private lateinit var accountId: String
    private var captured = false

    // Scope for the lock-holding login coroutine; cancelled in onDestroy so an abandoned login
    // (user backs out before a sessionKey appears) releases the shared jar lock.
    private val scope = MainScope()
    // Completed by tryCapture() once harvesting is done (or by onDestroy if login is abandoned),
    // so the lock-holding coroutine below stays suspended — and keeps the jar lock — for the whole
    // interactive login rather than releasing it between WebView callbacks.
    private val loginDone = kotlinx.coroutines.CompletableDeferred<Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Existing account (re-auth) reuses its id; a fresh login mints a new one.
        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: storage.createAccount()

        // The WebView cookie jar is a process-global; a concurrent background re-solve
        // (RefreshWorker / RefreshAction) can clearJar() at any moment and wipe this live login
        // session, or we could clear/harvest across theirs. Hold the shared jar lock for the whole
        // login — clear → interactive load → harvest — so no background component touches the jar
        // mid-login. The lock releases when loginDone completes (capture) or onDestroy cancels.
        scope.launch {
            CookieJarLock.mutex.withLock {
                startLogin()
                loginDone.await()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startLogin() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val startUrl = intent.getStringExtra(EXTRA_START_URL)
        // Wipe any prior account's session before logging in so the WebView can't silently reuse
        // it and harvest the wrong account's cookies. Skip when continuing an in-flight attempt via
        // an explicit startUrl (e.g. a magic-link click) -- clearing here would drop the pending
        // session/anti-CSRF cookie the original /login page + email submission just set, which is
        // exactly what makes claude.ai treat the magic-link visit as a same-browser continuation
        // instead of a cross-device verification challenge.
        if (startUrl == null) {
            cm.removeAllCookies(null)
            cm.flush()
        }

        webView = WebView(this).apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Persist the exact UA so cf_clearance stays valid for headless fetches.
            storage.setUserAgent(accountId, settings.userAgentString)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    tryCapture()
                }
            }
            loadUrl(startUrl ?: Const.LOGIN_URL)
        }
        setContentView(webView)

        // claude.ai's post-login/post-code-verify transition to the main app is a client-side SPA
        // route change (History API), not a full document navigation -- WebViewClient.onPageFinished
        // never fires again after it, so the onPageFinished-triggered tryCapture() above can miss a
        // sessionKey cookie that gets set purely via an XHR/fetch response. Poll as a fallback so
        // login still completes for that path (e.g. after "Continue with Google", or a code-verify
        // that lands straight on the app instead of reloading the login page).
        pollForSessionCookie()
    }

    private fun pollForSessionCookie(attemptsLeft: Int = 60) {
        if (captured || attemptsLeft <= 0) return
        Handler(Looper.getMainLooper()).postDelayed({
            tryCapture()
            if (!captured) pollForSessionCookie(attemptsLeft - 1)
        }, 1500)
    }

    private fun tryCapture() {
        if (captured) return
        val cookies = CookieManager.getInstance().getCookie(Const.BASE) ?: return
        if (!cookies.contains("${Const.COOKIE_SESSION}=")) return

        captured = true
        CookieManager.getInstance().flush()
        CookieHarvester.harvest(storage, accountId)
        // Release the jar lock now that harvesting is done; the follow-up work below only touches
        // per-account storage + WorkManager, not the cookie jar.
        loginDone.complete(Unit)

        // Discover org + first snapshot, then schedule periodic refresh.
        CoroutineScope(Dispatchers.Main).launch {
            RefreshScheduler.ensurePeriodic(this@LoginActivity)
            RefreshScheduler.refreshNow(this@LoginActivity)
            UsageWidget.updateAll(this@LoginActivity)
            Toast.makeText(this@LoginActivity, "Signed in", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, accountId))
            finish()
        }
    }

    override fun onDestroy() {
        // If login was abandoned before capture, unblock the lock-holding coroutine so the shared
        // jar lock is released; then cancel the scope.
        loginDone.complete(Unit)
        scope.cancel()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "accountId"
        const val EXTRA_START_URL = "startUrl"
    }
}
