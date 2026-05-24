plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    id("oci-fetch.locking")
    id("oci-fetch.testing")
    id("oci-fetch.native-tooling")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
    basePath = layout.buildDirectory.dir("reports/detekt")
}

tasks.named<dev.detekt.gradle.Detekt>("detekt") {
    val sources = kotlin.sourceSets
        .filter { !it.name.contains("test", ignoreCase = true) }
        .flatMap { it.kotlin.sourceDirectories }
    setSource(sources)
    reports {
        sarif.required = true
        html.required = true
    }
}

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

java {
    toolchain{
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    jvm()
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
    linuxX64() {
        binaries.executable {
            entryPoint = "main"
        }
        // FIXME: Remove when Clikt fixes the issue. See https://kotl.in/disable-native-cache
        @OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)
        binaries.all {
            disableNativeCache(
                version = org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion.`2_4_0`,
                reason = "Workaround for Clikt 5.x duplicate symbol linker error (selfAndAncestors)."
            )
        }
    }
    mingwX64 {
        binaries.executable {
            entryPoint = "main"
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
            implementation(libs.ktor.client.auth)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.clikt)
            implementation(libs.kotlincrypto.hash.sha2)
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
