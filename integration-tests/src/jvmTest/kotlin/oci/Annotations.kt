package oci

actual typealias BeforeClass = org.junit.jupiter.api.BeforeAll
actual typealias AfterClass = org.junit.jupiter.api.AfterAll

actual fun skipTest(reason: String): Nothing {
    throw org.opentest4j.TestAbortedException(reason)
}
