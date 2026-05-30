plugins {
    id("oci-fetch.kmp-conventions")
    id("oci-fetch.testing")
    id("oci-fetch.locking")
}

kotlin {
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(project(":oci-fetch-lib"))
            implementation(kotlin("test"))
            implementation(libs.junit.jupiter)
            implementation(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.testcontainers)
            implementation(libs.testcontainers.junit.jupiter)
        }
    }
}

