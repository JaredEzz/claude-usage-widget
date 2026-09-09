package com.usage.claudewidget.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Single-source storage for the OpenCode Go widget.
 *
 *  - [secure]   EncryptedSharedPreferences holding the opencode-go API key (sk-...).
 *  - [snapshot] Plain prefs: the non-secret usage snapshot the widget renders + last error.
 *
 * Unlike the multi-account Claude/Antigravity builds there are no accounts and no per-widget
 * bindings: every widget instance renders this one shared snapshot.
 */
class UsageStore private constructor(
    private val secure: SharedPreferences,
    private val snapshot: SharedPreferences,
) {
    // ---- credentials (encrypted) ----

    fun apiKey(): String? = secure.getString(KEY_API_KEY, null)
    fun setApiKey(v: String?) {
        if (v.isNullOrBlank()) {
            secure.edit().remove(KEY_API_KEY).apply()
        } else {
            secure.edit().putString(KEY_API_KEY, v.trim()).apply()
        }
    }

    fun hasKey(): Boolean = !apiKey().isNullOrBlank()

    // ---- usage snapshot (plain) ----

    fun rollingUtil(): Float = snapshot.getFloat("r5h_util", -1f)
    fun rollingReset(): Long = snapshot.getLong("r5h_reset", 0L)
    fun weeklyUtil(): Float = snapshot.getFloat("wk_util", -1f)
    fun weeklyReset(): Long = snapshot.getLong("wk_reset", 0L)
    fun monthlyUtil(): Float = snapshot.getFloat("mo_util", -1f)
    fun monthlyReset(): Long = snapshot.getLong("mo_reset", 0L)

    fun fetchedAt(): Long = snapshot.getLong("fetched_at", 0L)

    fun hasSnapshot(): Boolean = rollingUtil() >= 0f || weeklyUtil() >= 0f || monthlyUtil() >= 0f

    /** AuthState ordinal; widget shows "Tap to add key" when NEEDS_LOGIN / no key. */
    fun authState(): AuthState =
        AuthState.entries.getOrElse(snapshot.getInt("auth_state", 0)) { AuthState.OK }

    fun setAuthState(v: AuthState) =
        snapshot.edit().putInt("auth_state", v.ordinal).apply()

    /** Last transient failure reason for the status screen; null when the last fetch was fine. */
    fun lastError(): String? = snapshot.getString("last_error", null)

    fun setLastError(v: String?) = snapshot.edit().putString("last_error", v).apply()

    fun saveSnapshot(s: OpenCodeSnapshot) {
        snapshot.edit()
            .putFloat("r5h_util", s.rolling.utilization)
            .putLong("r5h_reset", s.rolling.resetsAtEpochMs)
            .putFloat("wk_util", s.weekly.utilization)
            .putLong("wk_reset", s.weekly.resetsAtEpochMs)
            .putFloat("mo_util", s.monthly.utilization)
            .putLong("mo_reset", s.monthly.resetsAtEpochMs)
            .putLong("fetched_at", s.fetchedAtEpochMs)
            .apply()
    }

    companion object {
        private const val KEY_API_KEY = "api_key"

        @Volatile private var instance: UsageStore? = null

        fun get(context: Context): UsageStore = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(app: Context): UsageStore {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                app,
                "opencode_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val snapshot = app.getSharedPreferences("opencode_usage", Context.MODE_PRIVATE)
            return UsageStore(secure, snapshot)
        }
    }
}

enum class AuthState { OK, NEEDS_LOGIN }
