package app.chspch.client.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes token rotation and reuses a session already refreshed by another request. */
internal class SessionLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> recover(
        rejectedToken: String,
        current: () -> T?,
        token: (T) -> String,
        refresh: suspend () -> T
    ): T = mutex.withLock {
        val session = current()
        if (session != null && token(session) != rejectedToken) session else refresh()
    }
}
