package com.usage.claudewidget.data

import android.webkit.CookieManager

/** Reads claude.ai cookies out of the WebView cookie jar into [storage] for [accountId]. */
object CookieHarvester {
    fun harvest(storage: AccountStorage, accountId: String): Boolean {
        val cm = CookieManager.getInstance()
        val raw = cm.getCookie(Const.BASE) ?: return false
        val map = raw.split(';')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()

        val session = map[Const.COOKIE_SESSION]
        val cf = map[Const.COOKIE_CF]
        var changed = false
        if (!session.isNullOrBlank()) { storage.setSessionKey(accountId, session); changed = true }
        if (!cf.isNullOrBlank()) { storage.setCfClearance(accountId, cf); changed = true }
        // Best-effort org id fallback straight from the cookie.
        map[Const.COOKIE_LAST_ORG]?.let {
            if (storage.orgId(accountId).isNullOrBlank()) storage.setOrgId(accountId, it)
        }
        return changed
    }
}
