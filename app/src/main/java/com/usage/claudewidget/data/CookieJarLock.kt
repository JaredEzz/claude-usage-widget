package com.usage.claudewidget.data

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide mutex serializing every critical section that touches the shared WebView
 * [android.webkit.CookieManager] jar (clear → seed → challenge → harvest).
 *
 * The jar is a single global owned by the WebView subsystem, but three independent entry points
 * mutate it: interactive login ([com.usage.claudewidget.auth.LoginActivity]), the silent
 * Cloudflare re-solve inside the periodic [com.usage.claudewidget.work.RefreshWorker], and the
 * same re-solve reached from a widget-tap [com.usage.claudewidget.widget.RefreshAction]. A
 * tap-refresh can run concurrently with the periodic worker (and either can overlap an in-progress
 * login), so without a shared lock one account's clearJar()/seed can interleave with another
 * account's harvest — cross-contaminating cf_clearance / sessionKey into the wrong slot.
 *
 * Hold this across the *entire* clear→seed→challenge→harvest span, never just one call.
 */
object CookieJarLock {
    val mutex = Mutex()
}
