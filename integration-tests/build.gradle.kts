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
    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "2m"
                }
            }
        }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs {
            testTask {
                useMocha {
                    timeout = "2m"
                }
            }
        }
    }
    linuxX64()
    mingwX64()

    sourceSets {
        commonTest.dependencies {
            implementation(project(":")) // Depend on the core KMP library
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}
