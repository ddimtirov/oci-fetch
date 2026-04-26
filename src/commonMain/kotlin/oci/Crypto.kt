package oci

import org.kotlincrypto.hash.sha2.SHA256

private const val HEX_CHARS = "0123456789abcdef"

/**
 * Computes the SHA-256 of [bytes] and returns a lowercase hex string (no `sha256:` prefix).
 */
internal fun sha256Hex(bytes: ByteArray): String {
    val hash = SHA256().digest(bytes)
    return buildString(hash.size * 2) {
        for (b in hash) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }
}
