package com.usage.claudewidget.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.usage.claudewidget.data.AccountStorage
import com.usage.claudewidget.data.AuthState
import com.usage.claudewidget.data.Const
import com.usage.claudewidget.widget.UsageWidget
import com.usage.claudewidget.work.RefreshScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

class LoginActivity : ComponentActivity() {

    private val storage by lazy { AccountStorage.get(this) }
    private lateinit var accountId: String
    private val scope = MainScope()

    private var serverSocket: ServerSocket? = null
    private var loopbackPort: Int = 0
    private var currentAuthUrl: String = ""

    private var isAuthenticatingState = mutableStateOf(false)
    private var statusMessageState = mutableStateOf("Opening browser for Google Sign-In...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: storage.createAccount()

        // Check if seed tokens were passed (e.g. from CLI / adb)
        val seedRefresh = intent.getStringExtra(EXTRA_SEED_REFRESH_TOKEN)
        if (!seedRefresh.isNullOrBlank()) {
            handleSeedLogin(seedRefresh)
            return
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val isAuth by remember { isAuthenticatingState }
                    val status by remember { statusMessageState }
                    LoginScreen(
                        isAuthenticating = isAuth,
                        statusMessage = status,
                        onOpenBrowser = { openBrowser(currentAuthUrl) },
                        onSubmitCode = { codeOrUrl -> submitManualCode(codeOrUrl) },
                    )
                }
            }
        }

        startOAuthServer()
    }

    private fun handleSeedLogin(seedRefresh: String) {
        storage.setRefreshToken(accountId, seedRefresh)
        val seedAccess = intent.getStringExtra(EXTRA_SEED_ACCESS_TOKEN)
        val seedProject = intent.getStringExtra(EXTRA_SEED_PROJECT_ID)
        val seedEmail = intent.getStringExtra(EXTRA_SEED_EMAIL)
        if (!seedAccess.isNullOrBlank()) storage.setAccessToken(accountId, seedAccess)
        if (!seedProject.isNullOrBlank()) storage.setProjectId(accountId, seedProject)
        if (!seedEmail.isNullOrBlank()) {
            storage.setEmail(accountId, seedEmail)
            storage.setLabel(accountId, seedEmail)
        }
        storage.setAuthState(accountId, AuthState.OK)
        onLoginComplete("Signed in via seed credentials")
    }

    private fun startOAuthServer() {
        scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                loopbackPort = server.localPort
                val redirectUri = "http://127.0.0.1:$loopbackPort/callback"
                val state = UUID.randomUUID().toString().substring(0, 12)
                val scopesJoined = Const.GOOGLE_SCOPES.joinToString("%20")
                currentAuthUrl = "${Const.GOOGLE_AUTH_URL}?client_id=${Const.GOOGLE_CLIENT_ID}&redirect_uri=$redirectUri&response_type=code&scope=$scopesJoined&access_type=offline&prompt=consent&state=$state"

                withContext(Dispatchers.Main) {
                    openBrowser(currentAuthUrl)
                }

                val socket = server.accept()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val firstLine = reader.readLine().orEmpty()
                val path = firstLine.substringAfter("GET ", "").substringBefore(" HTTP")
                val callbackUri = Uri.parse("http://127.0.0.1$path")
                val code = callbackUri.getQueryParameter("code")

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
                    <body style="font-family: -apple-system, Roboto, sans-serif; text-align: center; padding: 40px 20px; background: #0f172a; color: #f8fafc;">
                      <h2 style="color: #60a5fa;">✓ Antigravity Connected</h2>
                      <p>You're signed in! You can close this tab and return to the app.</p>
                    </body>
                    </html>
                """.trimIndent()

                val bytes = html.toByteArray(Charsets.UTF_8)
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(resp.toByteArray(Charsets.UTF_8))
                    write(bytes)
                    flush()
                }
                socket.close()
                server.close()

                if (!code.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        isAuthenticatingState.value = true
                        statusMessageState.value = "Authenticating with Google..."
                    }
                    val ok = exchangeCodeAndSave(code, redirectUri)
                    withContext(Dispatchers.Main) {
                        isAuthenticatingState.value = false
                        if (ok) {
                            onLoginComplete("Signed in successfully")
                        } else {
                            statusMessageState.value = "Authentication failed. Please try again."
                        }
                    }
                }
            } catch (_: Exception) {
                // Server closed
            }
        }
    }

    private fun openBrowser(url: String) {
        if (url.isBlank()) return
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(browserIntent)
        } catch (_: Exception) {
            statusMessageState.value = "Please open the sign-in URL manually or paste code below."
        }
    }

    private fun submitManualCode(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return
        val code = if (trimmed.contains("code=")) {
            Uri.parse(if (trimmed.startsWith("http")) trimmed else "http://dummy?$trimmed").getQueryParameter("code") ?: trimmed
        } else {
            trimmed
        }
        val redirectUri = "http://127.0.0.1:$loopbackPort/callback"
        scope.launch {
            isAuthenticatingState.value = true
            statusMessageState.value = "Exchanging authorization code..."
            val ok = exchangeCodeAndSave(code, redirectUri)
            isAuthenticatingState.value = false
            if (ok) {
                onLoginComplete("Signed in successfully")
            } else {
                statusMessageState.value = "Invalid authorization code. Please try again."
            }
        }
    }

    private suspend fun exchangeCodeAndSave(code: String, redirectUri: String): Boolean = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val form = FormBody.Builder()
            .add("client_id", Const.GOOGLE_CLIENT_ID)
            .add("client_secret", Const.GOOGLE_CLIENT_SECRET)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .build()

        val req = Request.Builder()
            .url(Const.GOOGLE_TOKEN_URL)
            .post(form)
            .build()

        try {
            val tokenResp = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                JSONObject(resp.body?.string().orEmpty())
            }

            val accessToken = tokenResp.optString("access_token")
            val refreshToken = tokenResp.optString("refresh_token")
            val expiresIn = tokenResp.optLong("expires_in", 3600L)
            if (accessToken.isBlank()) return@withContext false

            // Fetch user email
            val userReq = Request.Builder()
                .url(Const.GOOGLE_USER_INFO_URL)
                .header("Authorization", "Bearer $accessToken")
                .build()

            val email = client.newCall(userReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    JSONObject(resp.body?.string().orEmpty()).optString("email")
                } else null
            }

            // Resolve project ID
            val metaJson = JSONObject().apply {
                put("metadata", JSONObject().apply {
                    put("ideType", "ANTIGRAVITY")
                    put("platform", "PLATFORM_UNSPECIFIED")
                    put("pluginType", "GEMINI")
                })
            }
            val projReq = Request.Builder()
                .url(Const.LOAD_CODE_ASSIST_URL)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", Const.USER_AGENT)
                .header("Content-Type", "application/json")
                .post(metaJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val projectId = client.newCall(projReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val comp = json.opt("cloudaicompanionProject")
                    when (comp) {
                        is String -> comp
                        is JSONObject -> comp.optString("id")
                        else -> null
                    }
                } else null
            }

            storage.setAccessToken(accountId, accessToken)
            if (refreshToken.isNotBlank()) {
                storage.setRefreshToken(accountId, refreshToken)
            }
            storage.setTokenExpiresAt(accountId, System.currentTimeMillis() + expiresIn * 1000L)
            if (!projectId.isNullOrBlank()) {
                storage.setProjectId(accountId, projectId)
            }
            if (!email.isNullOrBlank()) {
                storage.setEmail(accountId, email)
                storage.setLabel(accountId, email)
            }
            storage.setAuthState(accountId, AuthState.OK)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onLoginComplete(message: String) {
        scope.launch(Dispatchers.Main) {
            RefreshScheduler.ensurePeriodic(this@LoginActivity)
            RefreshScheduler.refreshNow(this@LoginActivity, accountId)
            UsageWidget.updateAll(this@LoginActivity)
            Toast.makeText(this@LoginActivity, message, Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, accountId))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        scope.cancel()
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "accountId"
        const val EXTRA_SEED_REFRESH_TOKEN = "seedRefreshToken"
        const val EXTRA_SEED_ACCESS_TOKEN = "seedAccessToken"
        const val EXTRA_SEED_PROJECT_ID = "seedProjectId"
        const val EXTRA_SEED_EMAIL = "seedEmail"
        const val EXTRA_START_URL = "startUrl"
        const val EXTRA_SEED_SESSION_KEY = "seedSessionKey"
        const val EXTRA_SEED_CF_CLEARANCE = "seedCfClearance"
    }
}

@Composable
private fun LoginScreen(
    isAuthenticating: Boolean,
    statusMessage: String,
    onOpenBrowser: () -> Unit,
    onSubmitCode: (String) -> Unit,
) {
    var manualInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Google Antigravity", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(statusMessage, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(24.dp))

        if (isAuthenticating) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) {
                Text("Open Google Sign-In in Browser")
            }

            Spacer(Modifier.height(32.dp))

            Text("Or paste redirect URL / code:", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualInput,
                onValueChange = { manualInput = it },
                label = { Text("Code or localhost URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onSubmitCode(manualInput) },
                enabled = manualInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Complete Sign-In")
            }
        }
    }
}
