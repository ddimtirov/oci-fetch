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
    private val tokens = mutableMapOf<String, String>()

    /**
     * Returns a cached token for the given [tokenUrl], or calls [fetch] to get a new one
     * if no valid cached entry exists. Concurrent callers requesting the same [tokenUrl]
     * are serialized so that [fetch] is invoked at most once per key.
     */
    @Suppress("ReturnCount")
    suspend fun get(tokenUrl: String, fetch: suspend (String) -> String): String {
        // First check if token exists (without acquiring lock)
        mutex.withLock { tokens[tokenUrl]?.let { return it } }

        // Acquire global lock to prevent races on the same challenge
        return mutex.withLock {
            // Double-check after acquiring the global lock
            tokens[tokenUrl]?.let { return it }

            // Fetch the token
            val token = fetch(tokenUrl)
            tokens[tokenUrl] = token
            token
        }
    }

    /** Removes the cached token for the given [tokenUrl], if any. */
    suspend fun invalidate(tokenUrl: String) = mutex.withLock { tokens.remove(tokenUrl) }

    /** Removes all cached tokens. */
    suspend fun invalidateAll() = mutex.withLock { tokens.clear() }

    /** Returns the number of cached entries (mainly for testing). */
    suspend fun size(): Int = mutex.withLock { tokens.size }

    companion object {
        /** Global shared credentials store instance. */
        val default: CredentialsStore = CredentialsStore()
    }
}
