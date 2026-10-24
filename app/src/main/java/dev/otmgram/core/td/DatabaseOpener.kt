package dev.otmgram.core.td

import dev.otmgram.core.log.AppLog
import org.drinkless.tdlib.TdApi

/**
 * Opens TDLib's database with its key. The local database is deleted only when it's
 * certain nothing can open it again, and the key file only once the database opened
 * without it; other failures (a busy Keystore, a full disk) are retried.
 */
internal class DatabaseOpener(
    private val load: () -> StoredKey,
    private val deleteKey: () -> Unit,
    /** Sends the TDLib parameters with this key (null: none); calls back with the error, if any. */
    private val send: (key: ByteArray?, done: (TdApi.Error?) -> Unit) -> Unit,
    /** Deletes the local database, for a fresh login. */
    private val destroy: () -> Unit,
    private val later: (delayMs: Long, block: () -> Unit) -> Unit,
) {
    fun open(attempt: Int = 0) {
        when (val key = load()) {
            is StoredKey.Loaded -> send(key.bytes) { error ->
                when {
                    error == null -> Unit
                    error.code == WRONG_KEY -> {
                        // Never encrypted with this key (the app stopped while switching).
                        AppLog.w(TAG, "Database isn't encrypted with its key, opening without")
                        openWithoutKey(staleKey = true, attempt)
                    }
                    else -> retry(error.message, attempt)
                }
            }
            StoredKey.Lost -> openWithoutKey(staleKey = true, attempt)
            StoredKey.None -> openWithoutKey(staleKey = false, attempt)
            is StoredKey.Unavailable -> {
                AppLog.w(TAG, "Database key can't be read right now", key.error)
                retry("Keystore unavailable", attempt)
            }
        }
    }

    /** Opens an unencrypted database; [staleKey]: a key file is there that it doesn't use. */
    private fun openWithoutKey(staleKey: Boolean, attempt: Int) {
        send(null) { error ->
            when {
                error == null -> if (staleKey) deleteKey()
                error.code == WRONG_KEY -> {
                    // Encrypted with a key that's gone: nothing can open it again. Start over
                    // with a fresh database (a new login); the chats themselves are on Telegram.
                    AppLog.e(TAG, "Database key is lost; deleting the local database")
                    destroy()
                }
                else -> retry(error.message, attempt)
            }
        }
    }

    private fun retry(reason: String, attempt: Int) {
        val delayMs = (RETRY_FIRST_MS shl attempt.coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS)
        AppLog.e(TAG, "Database didn't open ($reason); trying again in ${delayMs / 1000} s")
        later(delayMs) { open(attempt + 1) }
    }

    companion object {
        /** TDLib's error for a database that doesn't open with the given key. */
        const val WRONG_KEY = 401
        const val RETRY_FIRST_MS = 2_000L
        const val RETRY_MAX_MS = 60_000L
        private const val TAG = "TdClient"
    }
}
