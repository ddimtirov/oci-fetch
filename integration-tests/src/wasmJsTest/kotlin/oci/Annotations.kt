package oci

actual annotation class BeforeClass()
actual annotation class AfterClass()

actual fun skipTest(reason: String): Nothing {
    throw TestSkipException(reason)
}
