package com.usage.claudewidget.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** A signed-in Claude account. [id] is a short random UUID; [label] is a non-secret display name. */
data class Account(val id: String, val label: String)

/**
 * Multi-account storage. Every account is identified by a short random [accountId] and its
 * secrets/snapshot/binding keys are namespaced by that id, e.g. "sessionKey:<id>".
 *
 *  - [secure]   EncryptedSharedPreferences for credentials (sessionKey, cf_clearance, UA, orgId).
 *  - [accounts] Plain prefs: the set of known accountIds + a display label per account.
 *  - [snapshot] Plain prefs: the non-secret usage snapshot the widget renders, per account.
 *  - [bindings] Plain prefs: appWidgetId -> accountId (which widget shows which account).
 */
class AccountStorage private constructor(
    private val secure: SharedPreferences,
    private val accounts: SharedPreferences,
    private val snapshot: SharedPreferences,
    private val bindings: SharedPreferences,
) {
    // ---- accounts registry (plain) ----

    fun listAccounts(): List<Account> {
        val ids = accounts.getStringSet(KEY_ACCOUNT_IDS, emptySet()).orEmpty()
        return ids.map { id -> Account(id, accounts.getString("$LABEL_PREFIX$id", null) ?: id) }
            .sortedBy { it.label }
    }

    /** Registers a new account and returns its id. */
    fun createAccount(label: String? = null): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val ids = accounts.getStringSet(KEY_ACCOUNT_IDS, emptySet()).orEmpty().toMutableSet()
        val ordinal = ids.size + 1
        ids.add(id)
        accounts.edit()
            .putStringSet(KEY_ACCOUNT_IDS, ids)
            .putString("$LABEL_PREFIX$id", label ?: "Account $ordinal")
            .apply()
        return id
    }

    fun setLabel(accountId: String, label: String) {
        accounts.edit().putString("$LABEL_PREFIX$accountId", label).apply()
    }

    /** Whether to post a notification when this account's 5-hour usage window resets. */
    fun notifyOnReset(accountId: String): Boolean =
        accounts.getBoolean("$NOTIFY_PREFIX$accountId", false)

    fun setNotifyOnReset(accountId: String, enabled: Boolean) {
        accounts.edit().putBoolean("$NOTIFY_PREFIX$accountId", enabled).apply()
    }

    /** True while [accountId] is still in the registry (i.e. not signed out / removed). */
    fun accountExists(accountId: String): Boolean =
        accounts.getStringSet(KEY_ACCOUNT_IDS, emptySet()).orEmpty().contains(accountId)

    /** Clears an account's secrets + snapshot, unbinds any widgets, drops it from the registry. */
    fun removeAccount(accountId: String) {
        secure.edit()
            .remove("sessionKey:$accountId")
            .remove("cf_clearance:$accountId")
            .remove("user_agent:$accountId")
            .remove("org_id:$accountId")
            .apply()
        snapshot.edit()
            .remove("fh_util:$accountId")
            .remove("fh_reset:$accountId")
            .remove("wk_util:$accountId")
            .remove("wk_reset:$accountId")
            .remove("sc_label:$accountId")
            .remove("sc_util:$accountId")
            .remove("sc_reset:$accountId")
            .remove("fetched_at:$accountId")
            .remove("auth_state:$accountId")
            .apply()
        // Unbind any widgets pointing at this account.
        for ((widgetKey, boundId) in bindings.all) {
            if (boundId == accountId) bindings.edit().remove(widgetKey).apply()
        }
        val ids = accounts.getStringSet(KEY_ACCOUNT_IDS, emptySet()).orEmpty().toMutableSet()
        ids.remove(accountId)
        accounts.edit()
            .putStringSet(KEY_ACCOUNT_IDS, ids)
            .remove("$LABEL_PREFIX$accountId")
            .remove("$NOTIFY_PREFIX$accountId")
            .apply()
    }

    // ---- credentials (encrypted, per account) ----

    fun sessionKey(id: String): String? = secure.getString("sessionKey:$id", null)
    fun setSessionKey(id: String, v: String?) = secure.edit().putString("sessionKey:$id", v).apply()

    fun cfClearance(id: String): String? = secure.getString("cf_clearance:$id", null)
    fun setCfClearance(id: String, v: String?) = secure.edit().putString("cf_clearance:$id", v).apply()

    fun userAgent(id: String): String? = secure.getString("user_agent:$id", null)
    fun setUserAgent(id: String, v: String?) = secure.edit().putString("user_agent:$id", v).apply()

    fun orgId(id: String): String? = secure.getString("org_id:$id", null)
    fun setOrgId(id: String, v: String?) = secure.edit().putString("org_id:$id", v).apply()

    fun isLoggedIn(id: String): Boolean = !sessionKey(id).isNullOrBlank()

    // ---- usage snapshot (plain, read by the widget) ----

    fun fiveHourUtil(id: String): Float = snapshot.getFloat("fh_util:$id", -1f)
    fun fiveHourReset(id: String): Long = snapshot.getLong("fh_reset:$id", 0L)
    fun sevenDayUtil(id: String): Float = snapshot.getFloat("wk_util:$id", -1f)
    fun sevenDayReset(id: String): Long = snapshot.getLong("wk_reset:$id", 0L)

    /** Model-scoped weekly window (e.g. Fable); label is blank when the account has none. */
    fun scopedLabel(id: String): String = snapshot.getString("sc_label:$id", "").orEmpty()
    fun scopedUtil(id: String): Float = snapshot.getFloat("sc_util:$id", -1f)
    fun scopedReset(id: String): Long = snapshot.getLong("sc_reset:$id", 0L)
    fun hasScoped(id: String): Boolean = scopedLabel(id).isNotBlank() && scopedUtil(id) >= 0f

    fun fetchedAt(id: String): Long = snapshot.getLong("fetched_at:$id", 0L)

    fun hasSnapshot(id: String): Boolean = fiveHourUtil(id) >= 0f

    /** AuthState ordinal; widget shows "Tap to sign in" when NEEDS_LOGIN. */
    fun authState(id: String): AuthState =
        AuthState.entries.getOrElse(snapshot.getInt("auth_state:$id", 0)) { AuthState.OK }

    fun setAuthState(id: String, v: AuthState) =
        snapshot.edit().putInt("auth_state:$id", v.ordinal).apply()

    fun saveSnapshot(id: String, s: UsageSnapshot) {
        val editor = snapshot.edit()
            .putFloat("fh_util:$id", s.fiveHour.utilization)
            .putLong("fh_reset:$id", s.fiveHour.resetsAtEpochMs)
            .putFloat("wk_util:$id", s.sevenDay.utilization)
            .putLong("wk_reset:$id", s.sevenDay.resetsAtEpochMs)
            .putLong("fetched_at:$id", s.fetchedAtEpochMs)
        // Persist the scoped weekly window when present; clear it otherwise so a model that
        // drops off the account's limits doesn't leave a stale row behind on the widget.
        val sc = s.scopedWeekly
        if (sc != null) {
            editor.putString("sc_label:$id", sc.label)
                .putFloat("sc_util:$id", sc.window.utilization)
                .putLong("sc_reset:$id", sc.window.resetsAtEpochMs)
        } else {
            editor.remove("sc_label:$id")
                .remove("sc_util:$id")
                .remove("sc_reset:$id")
        }
        editor.apply()
    }

    // ---- widget bindings (plain) ----

    fun bindWidget(appWidgetId: Int, accountId: String) {
        bindings.edit().putString(appWidgetId.toString(), accountId).apply()
    }

    fun unbindWidget(appWidgetId: Int) {
        bindings.edit().remove(appWidgetId.toString()).apply()
    }

    fun accountIdFor(appWidgetId: Int): String? = bindings.getString(appWidgetId.toString(), null)

    /** Distinct accountIds that currently have at least one widget bound. */
    fun boundAccountIds(): Set<String> =
        bindings.all.values.mapNotNull { it as? String }.toSet()

    companion object {
        private const val KEY_ACCOUNT_IDS = "account_ids"
        private const val LABEL_PREFIX = "label:"
        private const val NOTIFY_PREFIX = "notify:"

        @Volatile private var instance: AccountStorage? = null

        fun get(context: Context): AccountStorage = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(app: Context): AccountStorage {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                app,
                "claude_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val accounts = app.getSharedPreferences("claude_accounts", Context.MODE_PRIVATE)
            val snapshot = app.getSharedPreferences("claude_usage", Context.MODE_PRIVATE)
            val bindings = app.getSharedPreferences("widget_bindings", Context.MODE_PRIVATE)
            return AccountStorage(secure, accounts, snapshot, bindings)
        }
    }
}

enum class AuthState { OK, NEEDS_LOGIN }
