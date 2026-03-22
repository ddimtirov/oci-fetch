plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("oci-fetch.locking")
    id("oci-fetch.testing")
    id("oci-fetch.example-run")
    id("oci-fetch.native-tooling")
}

group = "io.github.ddimtirov"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

kotlin {
    jvmToolchain(25)
    jvm()
    js(IR) {
        browser()
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    linuxX64()
    mingwX64 {
        binaries.executable {
            entryPoint = "example.main"
        }
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes") // suppressing incubation warning
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.logback.classic)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.curl)
        }
    }
}