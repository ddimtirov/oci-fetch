package oci

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class CredentialsStoreTest {

    @Test
    fun cachesTokenByUrl() = runTest {
        val store = CredentialsStore()
        var fetchCount = 0
        val token1 = store.get("https://auth.example.com/token?scope=a") { fetchCount++; "tok-a" }
        val token2 = store.get("https://auth.example.com/token?scope=a") { fetchCount++; "tok-b" }
        assertEquals("tok-a", token1)
        assertEquals("tok-a", token2)
        assertEquals(1, fetchCount)
    }

    @Test
    fun differentUrlsGetDifferentTokens() = runTest {
        val store = CredentialsStore()
        val t1 = store.get("https://auth.example.com/token?scope=a") { "tok-a" }
        val t2 = store.get("https://auth.example.com/token?scope=b") { "tok-b" }
        assertEquals("tok-a", t1)
        assertEquals("tok-b", t2)
        assertEquals(2, store.size())
    }

    @Test
    fun invalidateRemovesCachedEntry() = runTest {
        val store = CredentialsStore()
        store.get("https://auth.example.com/token") { "old" }
        store.invalidate("https://auth.example.com/token")
        val token = store.get("https://auth.example.com/token") { "new" }
        assertEquals("new", token)
    }

    @Test
    fun invalidateAllClearsEverything() = runTest {
        val store = CredentialsStore()
        store.get("https://a.com/token") { "a" }
        store.get("https://b.com/token") { "b" }
        assertEquals(2, store.size())
        store.invalidateAll()
        assertEquals(0, store.size())
    }

    @Test
    fun concurrentRequestsForSameKeyFetchOnlyOnce() = runTest {
        val store = CredentialsStore()
        var fetchCount = 0
        val results = (1..10).map {
            async {
                store.get("https://auth.example.com/token") {
                    fetchCount++
                    delay(50.milliseconds)
                    "tok"
                }
            }
        }.awaitAll()

        assertEquals(1, fetchCount)
        results.forEach { assertEquals("tok", it) }
    }

    @Test
    fun defaultInstanceIsSharedSingleton() {
        val a = CredentialsStore.default
        val b = CredentialsStore.default
        assertEquals(a, b)
    }
}
