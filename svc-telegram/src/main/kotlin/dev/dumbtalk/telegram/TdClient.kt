package dev.dumbtalk.telegram

import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A TDLib error turned into an exception the UI can show. */
class TelegramException(val code: Int, message: String) : Exception(message)

/** Suspending calls over one TDLib client; updates go to [onUpdate]. */
internal class TdClient(onUpdate: (TdApi.Object) -> Unit) {
    private val client: Client = Client.create(
        { update -> onUpdate(update) },
        { error -> android.util.Log.w(TAG, "update handler failed", error) },
        { error -> android.util.Log.w(TAG, "TDLib callback failed", error) },
    )

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T = suspendCancellableCoroutine { continuation ->
        client.send(function) { result ->
            if (result is TdApi.Error) {
                continuation.resumeWithException(TelegramException(result.code, friendlyError(result)))
            } else {
                continuation.resume(result as T)
            }
        }
    }

    /** Fire and forget, for calls whose result we don't need. */
    fun post(function: TdApi.Function<*>) = client.send(function) { }

    companion object {
        private const val TAG = "TdClient"

        init {
            // Keep TDLib's own logging to warnings and errors.
            runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        }

        private fun friendlyError(error: TdApi.Error): String = when {
            error.message.contains("PHONE_CODE_INVALID") -> "That code is wrong. Try again."
            error.message.contains("PHONE_CODE_EXPIRED") -> "That code has expired. Go back and request a new one."
            error.message.contains("PASSWORD_HASH_INVALID") -> "Wrong password. Try again."
            error.message.contains("PHONE_NUMBER_INVALID") -> "That phone number isn't valid. Include the country code."
            error.message.contains("API_ID_INVALID") || error.message.contains("API_ID_PUBLISHED_FLOOD") ->
                "Telegram rejected the API ID and hash."
            error.message.startsWith("Too Many Requests") -> "Telegram is rate limiting this account. Wait a while and try again."
            else -> error.message
        }
    }
}
