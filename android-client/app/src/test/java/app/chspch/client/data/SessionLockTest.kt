package app.chspch.client.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SessionLockTest {
    @Test fun concurrentUnauthorizedRequestsRotateTokenOnce() = runBlocking {
        val lock = SessionLock()
        var current = "expired"
        var rotations = 0
        val results = (1..20).map {
            async {
                lock.recover("expired", { current }, { it }) {
                    delay(10)
                    rotations++
                    current = "new"
                    current
                }
            }
        }.awaitAll()
        assertEquals(1, rotations)
        assertTrue(results.all { it == "new" })
    }

    @Test fun failedRefreshPreservesSessionAndAllowsRetry() = runBlocking {
        val lock = SessionLock()
        var current = "expired"
        try {
            lock.recover("expired", { current }, { it }) { throw IOException("offline") }
            fail("Expected network failure")
        } catch (_: IOException) { }
        assertEquals("expired", current)
        val recovered = lock.recover("expired", { current }, { it }) { current = "new"; current }
        assertEquals("new", recovered)
    }
}
