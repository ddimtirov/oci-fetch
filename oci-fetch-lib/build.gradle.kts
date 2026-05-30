plugins {
    id("maven-publish")
    id("oci-fetch.kmp-conventions")
    id("oci-fetch.testing")
    id("oci-fetch.locking")
}

tasks.named<dev.detekt.gradle.Detekt>("detekt") {
    source = files(kotlin.sourceSets
        .filter { !it.name.contains("test", ignoreCase = true) }
        .flatMap { it.kotlin.sourceDirectories }
    ).asFileTree
}

kotlin {
    jvm()
    linuxX64()
    mingwX64()
    js {
        browser()
        nodejs()
        binaries.library()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.auth)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.json.schema.validator)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
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
