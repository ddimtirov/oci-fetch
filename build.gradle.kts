plugins {
    id("oci-fetch.kmp-conventions")
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
    linuxX64 {
        binaries.executable {
            entryPoint = "main"
        }
    }
    mingwX64 {
        binaries.executable {
            entryPoint = "main"
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.auth)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.clikt)
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
