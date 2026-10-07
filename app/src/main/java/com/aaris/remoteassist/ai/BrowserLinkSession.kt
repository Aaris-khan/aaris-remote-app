package com.aaris.remoteassist.ai

import android.content.Context
import com.aaris.remoteassist.backend.CloudflareBackend
import com.aaris.remoteassist.pairing.BackendSessionCloser
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BrowserLinkTicket(
    val sessionId: String,
    val controllerToken: String,
    val expiresAtMs: Long,
    val browserBaseUrl: String = CloudflareBackend.BASE_URL
) {
    val link: String get() = "${browserBaseUrl.trimEnd('/')}/remote/#$sessionId.$controllerToken"
}

/** Process-local: killing the capture process must never silently restore consent. */
object BrowserLinkRuntime {
    @Volatile var ticket: BrowserLinkTicket? = null
        private set
    @Volatile var sharing: Boolean = false
        private set
    @Volatile var message: String = "Copy a link, then paste it into an AI with browser control."
        private set
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun prepare(value: BrowserLinkTicket) { ticket = value; sharing = false; update("Link copied • finish screen sharing setup") }
    fun started(sessionId: String) {
        if (ticket?.sessionId != sessionId) return
        sharing = true; update("AI link ready • paste the copied link into your AI")
    }
    fun update(value: String) { message = value; listeners.forEach { runCatching(it) } }
    fun clear(sessionId: String?, reason: String = "AI link ended • create a new link to connect again") {
        if (sessionId == null || ticket?.sessionId != sessionId) return
        ticket = null; sharing = false; update(reason)
    }
    fun addListener(listener: () -> Unit) { listeners += listener; listener() }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
}

object BrowserLinkBackend {
    private fun validatedBrowserBaseUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: return CloudflareBackend.BASE_URL
        val pathOk = uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null ||
            !pathOk
        ) {
            return CloudflareBackend.BASE_URL
        }
        return "https://${uri.rawAuthority}"
    }

    suspend fun create(context: Context): BrowserLinkTicket {
        var created: BrowserLinkTicket? = null
        try {
            return withContext(Dispatchers.IO) {
                val root = CloudflareBackend.publicRequest("POST", "/v1/browser-sessions")
                val id = root.getString("sessionId")
                CloudflareBackend.rememberToken(context, id, root.getString("hostToken"))
                BrowserLinkTicket(
                    sessionId = id,
                    controllerToken = root.getString("controllerToken"),
                    expiresAtMs = root.getLong("expiresAtMs"),
                    browserBaseUrl = validatedBrowserBaseUrl(
                        root.optString("browserBaseUrl", CloudflareBackend.BASE_URL)
                    )
                ).also { created = it }
            }
        } catch (error: Exception) {
            // Cancellation may happen after the server creates a session but
            // before withContext delivers it to the Activity.
            created?.let { BackendSessionCloser.close(context, it.sessionId) }
            throw error
        }
    }
}
