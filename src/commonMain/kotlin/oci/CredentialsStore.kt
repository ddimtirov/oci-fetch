package oci

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory credentials store that caches bearer tokens keyed by their token URL.
 * Coordinates concurrent requests for the same challenge to prevent duplicate token fetches.
 *
 * Instances can be shared between multiple OCI clients. A [default] global instance
 * is provided for convenience.
 *
 * The design does not preclude adding persistence in the future — the [get] method
 * accepts a suspend factory that could be backed by a persistent cache.
 */
class CredentialsStore {
    private val mutex = Mutex()
    private val tokens = mutableMapOf<String, Entry>()

    /**
     * Returns a cached token for the given [tokenUrl], or calls [fetch] to obtain one
     * if no valid cached entry exists. Concurrent callers requesting the same [tokenUrl]
     * are serialized so that [fetch] is invoked at most once per key.
     */
    suspend fun get(tokenUrl: String, fetch: suspend (String) -> String): String {
        mutex.withLock {
            tokens[tokenUrl]?.token?.let { return it }
        }

        // Acquire per-key lock to prevent races on the same challenge
        val keyMutex = mutex.withLock {
            // Double-check after acquiring the global lock
            tokens[tokenUrl]?.token?.let { return it }
            tokens.getOrPut(tokenUrl) { Entry(keyMutex = Mutex()) }
        }.keyMutex

        return keyMutex.withLock {
            // Check again — another coroutine may have populated while we waited
            mutex.withLock { tokens[tokenUrl] }
                ?.token
                ?.let { return it }

            fetch(tokenUrl).also { token ->
                mutex.withLock {
                    tokens[tokenUrl] = Entry(token = token, keyMutex = keyMutex)
                }
            }
        }
    }

    /** Removes the cached token for the given [tokenUrl], if any. */
    suspend fun invalidate(tokenUrl: String) {
        mutex.withLock { tokens.remove(tokenUrl) }
    }

    /** Removes all cached tokens. */
    suspend fun invalidateAll() {
        mutex.withLock { tokens.clear() }
    }

    /** Returns the number of cached entries (mainly for testing). */
    suspend fun size(): Int = mutex.withLock { tokens.count { it.value.token != null } }

    private class Entry(val token: String? = null, val keyMutex: Mutex = Mutex())

    companion object {
        /** Global shared credentials store instance. */
        val default: CredentialsStore = CredentialsStore()
    }
}
