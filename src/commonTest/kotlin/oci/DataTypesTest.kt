package oci

import kotlin.test.Test
import kotlin.test.assertEquals

class DataTypesTest {
    @Test
    fun testParseSimple() {
        val ref = OciRef.parse("registry.example.com/myapp")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("latest", ref.reference)
    }

    @Test
    fun testParseWithTag() {
        val ref = OciRef.parse("registry.example.com/myapp:v1.0")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("v1.0", ref.reference)
    }

    @Test
    fun testParseWithDigest() {
        val ref = OciRef.parse("registry.example.com/myapp@sha256:abc123")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)
        assertEquals("sha256:abc123", ref.reference)
    }

    @Test
    fun testParseWithPath() {
        val ref = OciRef.parse("registry-1.docker.io/library/alpine:latest")
        assertEquals("registry-1.docker.io", ref.registry)
        assertEquals("library/alpine", ref.repository)
        assertEquals("latest", ref.reference)
    }

    @Test
    fun testUrlEncode() {
        val encoded = urlEncode("hello world")
        assertEquals("hello%20world", encoded)
    }

    @Test
    fun testUrlEncode_specialChars() {
        val encoded = urlEncode("a/b")
        assertEquals("a%2fb", encoded.lowercase())
    }
}
