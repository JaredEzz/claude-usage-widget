package com.usage.claudewidget.data

object Const {
    private fun deobfuscate(bytes: ByteArray, key: Byte = 42): String {
        return String(ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key.toInt()).toByte() }, Charsets.UTF_8)
    }

    val GOOGLE_CLIENT_ID: String by lazy {
        deobfuscate(byteArrayOf(0x1b, 0x1a, 0x1d, 0x1b, 0x1a, 0x1a, 0x1c, 0x1a, 0x1c, 0x1a, 0x1f, 0x13, 0x1b, 0x07, 0x5e, 0x47, 0x42, 0x59, 0x59, 0x43, 0x44, 0x18, 0x42, 0x18, 0x1b, 0x46, 0x49, 0x58, 0x4f, 0x18, 0x19, 0x1f, 0x5c, 0x5e, 0x45, 0x46, 0x45, 0x40, 0x42, 0x1e, 0x4d, 0x1e, 0x1a, 0x19, 0x4f, 0x5a, 0x04, 0x4b, 0x5a, 0x5a, 0x59, 0x04, 0x4d, 0x45, 0x45, 0x4d, 0x46, 0x4f, 0x5f, 0x59, 0x4f, 0x58, 0x49, 0x45, 0x44, 0x5e, 0x4f, 0x44, 0x5e, 0x04, 0x49, 0x45, 0x47))
    }
    val GOOGLE_CLIENT_SECRET: String by lazy {
        deobfuscate(byteArrayOf(0x6d, 0x65, 0x69, 0x79, 0x7a, 0x72, 0x07, 0x61, 0x1f, 0x12, 0x6c, 0x7d, 0x78, 0x1e, 0x12, 0x1c, 0x66, 0x4e, 0x66, 0x60, 0x1b, 0x47, 0x66, 0x68, 0x12, 0x59, 0x72, 0x69, 0x1e, 0x50, 0x1c, 0x5b, 0x6e, 0x6b, 0x4c))
    }
    const val GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val GOOGLE_USER_INFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
    val GOOGLE_SCOPES = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
    )

    const val CLOUDCODE_BASE = "https://cloudcode-pa.googleapis.com"
    const val LOAD_CODE_ASSIST_URL = "$CLOUDCODE_BASE/v1internal:loadCodeAssist"
    const val QUOTA_SUMMARY_URL = "$CLOUDCODE_BASE/v1internal:retrieveUserQuotaSummary"
    const val USER_AGENT = "antigravity"

    // Legacy Claude endpoints (optional fallback)
    const val BASE = "https://claude.ai"
    const val ORGS_URL = "$BASE/api/organizations"
    fun usageUrl(orgId: String) = "$BASE/api/organizations/$orgId/usage"
    const val LOGIN_URL = "$BASE/login"
    const val CHALLENGE_URL = "$BASE/new"
    const val COOKIE_SESSION = "sessionKey"
    const val COOKIE_CF = "cf_clearance"
    const val COOKIE_LAST_ORG = "lastActiveOrg"

    const val REFRESH_INTERVAL_MIN = 15L
    const val WORK_NAME = "antigravity-usage-refresh"
}
