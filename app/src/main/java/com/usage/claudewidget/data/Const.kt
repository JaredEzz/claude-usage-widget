package com.usage.claudewidget.data

object Const {
    // Official OpenCode Go usage API (same one the opencode.ai dashboard calls).
    // Auth: Authorization: Bearer <opencode-go API key> (an sk-... key from Console > API Keys).
    const val USAGE_URL = "https://opencode.ai/zen/go/v1/usage"

    const val REFRESH_INTERVAL_MIN = 15L
    const val WORK_NAME = "opencode-usage-refresh"
}
