package com.m4lych.oblivion.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.m4lych.oblivion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed interface DiscordAuthResult {
    data class Started(val authorizeUrl: String) : DiscordAuthResult
    data class Connected(val username: String) : DiscordAuthResult
    data class Error(val message: String) : DiscordAuthResult
    data object Disconnected : DiscordAuthResult
}

object DiscordAuthEvents {
    private val mutableEvents = MutableSharedFlow<DiscordAuthResult>(replay = 1, extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()
    fun emit(result: DiscordAuthResult) { mutableEvents.tryEmit(result) }
}

object DiscordAuthClient {
    suspend fun start(context: Context, apiBaseUrl: String = BuildConfig.OBLIVION_API_BASE_URL): DiscordAuthResult = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(apiBaseUrl) ?: return@withContext DiscordAuthResult.Error("Set the public HTTPS Oblivion API URL in the build configuration first.")
        val store = SecureSessionStore(context.applicationContext)
        store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
        val verifierBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        store.put(SecureSessionStore.DISCORD_PKCE_VERIFIER, verifier)
        val response = runCatching {
            requestJson(URL("$base/api/discord/oauth/start"), "POST", JSONObject().put("client_code_challenge", challenge), null)
        }.getOrElse { error ->
            store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
            return@withContext DiscordAuthResult.Error(error.message ?: "Could not reach the Oblivion API." )
        }
        if (response.first !in 200..299) {
            store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
            val message = if (response.second.optString("error") == "feature_not_configured") {
                val missing = response.second.optJSONArray("missing")
                val details = missing?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
                if (details.isEmpty()) "Discord sign-in is not configured on the Oblivion service."
                else "Discord sign-in service configuration is incomplete: ${details.joinToString()}"
            } else response.second.optString("error").takeIf(String::isNotBlank)
                ?: "Discord sign-in is not configured on the Oblivion service."
            return@withContext DiscordAuthResult.Error(message)
        }
        val authorizeUrl = response.second.optString("authorize_url")
        val parsed = runCatching { Uri.parse(authorizeUrl) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host != "discord.com" || parsed.path != "/oauth2/authorize") {
            store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
            return@withContext DiscordAuthResult.Error("The Oblivion API returned an invalid Discord authorization URL.")
        }
        DiscordAuthResult.Started(authorizeUrl)
    }

    suspend fun complete(context: Context, callback: Uri, apiBaseUrl: String = BuildConfig.OBLIVION_API_BASE_URL): DiscordAuthResult = withContext(Dispatchers.IO) {
        if (callback.scheme != "oblivion" || callback.host != "auth" || callback.path != "/discord") return@withContext DiscordAuthResult.Error("This is not a recognized Oblivion sign-in callback.")
        val oauthError = callback.getQueryParameter("error")
        if (!oauthError.isNullOrBlank()) {
            SecureSessionStore(context.applicationContext).remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
            return@withContext DiscordAuthResult.Error("Discord sign-in was cancelled or could not be authorized. You can try again.")
        }
        val code = callback.getQueryParameter("code")
        if (code.isNullOrBlank() || code.length > 256) return@withContext DiscordAuthResult.Error("The Discord sign-in callback did not include a valid one-time code.")
        val base = validateBaseUrl(apiBaseUrl) ?: return@withContext DiscordAuthResult.Error("The Oblivion API is not configured for this build.")
        val store = SecureSessionStore(context.applicationContext)
        val verifier = store.get(SecureSessionStore.DISCORD_PKCE_VERIFIER)
            ?: return@withContext DiscordAuthResult.Error("This sign-in was started by another app or expired. Start Discord sign-in again from Oblivion.")
        val response = runCatching {
            requestJson(URL("$base/api/discord/oauth/exchange"), "POST", JSONObject().put("code", code).put("client_verifier", verifier), null)
        }.getOrElse { error ->
            store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
            return@withContext DiscordAuthResult.Error(error.message ?: "Could not complete sign-in with the Oblivion API.")
        }
        if (response.first !in 200..299) {
            store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
            return@withContext DiscordAuthResult.Error("Discord sign-in failed or expired. Start it again from Oblivion.")
        }
        val token = response.second.optString("access_token")
        if (token.length < 32 || response.second.optString("token_type") != "Bearer") {
            store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
            return@withContext DiscordAuthResult.Error("The Oblivion API returned an invalid session response.")
        }
        store.put(SecureSessionStore.SESSION_TOKEN, token)
        store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
        val profile = runCatching { requestJson(URL("$base/api/me"), "GET", null, token) }.getOrNull()
        if (profile == null || profile.first !in 200..299 || discordUsername(profile.second) == null) {
            store.remove(SecureSessionStore.SESSION_TOKEN)
            return@withContext DiscordAuthResult.Error("The session was created, but Oblivion could not confirm the linked Discord profile. Try again.")
        }
        val username = profile.second.let(::discordUsername) ?: "Linked Discord account"
        DiscordAuthResult.Connected(username)
    }

    suspend fun currentUsername(context: Context, apiBaseUrl: String = BuildConfig.OBLIVION_API_BASE_URL): String? = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(apiBaseUrl) ?: return@withContext null
        val token = SecureSessionStore(context.applicationContext).get(SecureSessionStore.SESSION_TOKEN) ?: return@withContext null
        val response = runCatching { requestJson(URL("$base/api/me"), "GET", null, token) }.getOrNull() ?: return@withContext null
        if (response.first == 401) SecureSessionStore(context.applicationContext).remove(SecureSessionStore.SESSION_TOKEN)
        if (response.first !in 200..299) return@withContext null
        discordUsername(response.second)
    }

    suspend fun signOut(context: Context, apiBaseUrl: String = BuildConfig.OBLIVION_API_BASE_URL): Boolean = withContext(Dispatchers.IO) {
        val store = SecureSessionStore(context.applicationContext)
        val token = store.get(SecureSessionStore.SESSION_TOKEN)
        var revoked = false
        val base = validateBaseUrl(apiBaseUrl)
        if (token != null && base != null) {
            revoked = runCatching { requestJson(URL("$base/api/auth/logout"), "POST", JSONObject(), token).first in 200..299 }.getOrDefault(false)
        }
        store.remove(SecureSessionStore.SESSION_TOKEN)
        store.remove(SecureSessionStore.DISCORD_PKCE_VERIFIER)
        revoked
    }

    fun openAuthorizationPage(context: Context, authorizeUrl: String) {
        val parsed = Uri.parse(authorizeUrl)
        require(parsed.scheme == "https" && parsed.host == "discord.com" && parsed.path == "/oauth2/authorize")
        context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun validateBaseUrl(value: String): String? = runCatching {
        val uri = Uri.parse(value.trim())
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null || (uri.path?.isNotEmpty() == true && uri.path != "/")) null
        else value.trim().trimEnd('/')
    }.getOrNull()

    private fun discordUsername(payload: JSONObject): String? {
        val identities = payload.optJSONArray("identities") ?: return null
        for (index in 0 until identities.length()) {
            val identity = identities.optJSONObject(index) ?: continue
            if (identity.optString("provider") == "discord") {
                return identity.optString("provider_username").takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private fun requestJson(url: URL, method: String, body: JSONObject?, bearerToken: String?): Pair<Int, JSONObject> {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (bearerToken != null) setRequestProperty("Authorization", "Bearer $bearerToken")
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.use { it.bufferedReader(Charsets.UTF_8).readText().take(MAX_RESPONSE_CHARS) }.orEmpty()
            status to runCatching { JSONObject(payload) }.getOrDefault(JSONObject())
        } finally { connection.disconnect() }
    }

    private const val MAX_RESPONSE_CHARS = 32 * 1024
}
