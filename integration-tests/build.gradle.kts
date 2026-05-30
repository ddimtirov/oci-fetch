plugins {
    id("oci-fetch.kmp-conventions")
    id("oci-fetch.testing")
    id("oci-fetch.locking")
}

kotlin {
    jvm()
    linuxX64()
    mingwX64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }
    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "2m"
                }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(project(":oci-fetch-lib")) // Depend on the core KMP library
            implementation(kotlin("test"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}
