package oci

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OciClientWindowsTest {

    @Test
    fun testUrlEncode() {
        val encoded = urlEncode("hello world")
        assertEquals("hello%20world", encoded)
    }

    @Test
    fun testClientInstantiation() = runTest {
        val client = OciClient()
        client.close()
    }
}
