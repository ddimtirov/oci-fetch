plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("oci-fetch.locking")
    id("oci-fetch.testing")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(project(":"))
            implementation(libs.junit.jupiter)
            implementation(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(kotlin("test"))
            implementation(libs.ktor.client.core)
            implementation(libs.testcontainers)
            implementation(libs.testcontainers.junit.jupiter)
        }
    }
}
