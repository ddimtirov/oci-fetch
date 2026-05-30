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

    @Test
    fun testParseDockerConventions() {
        val ref1 = OciRef.parse("alpine")
        assertEquals("registry-1.docker.io", ref1.registry)
        assertEquals("library/alpine", ref1.repository)
        assertEquals("latest", ref1.reference)

        val ref2 = OciRef.parse("library/alpine:3.18")
        assertEquals("registry-1.docker.io", ref2.registry)
        assertEquals("library/alpine", ref2.repository)
        assertEquals("3.18", ref2.reference)

        val ref3 = OciRef.parse("localhost:5000/myapp")
        assertEquals("localhost:5000", ref3.registry)
        assertEquals("myapp", ref3.repository)
        assertEquals("latest", ref3.reference)
    }

    @Test
    fun testParseStrict() {
        val ref = OciRef.parseStrict("registry.example.com/myapp")
        assertEquals("registry.example.com", ref.registry)
        assertEquals("myapp", ref.repository)

        val ex = kotlin.runCatching { OciRef.parseStrict("alpine") }
        kotlin.test.assertTrue(ex.isFailure)
    }
}
