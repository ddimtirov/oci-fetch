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
    mingwX64 {
        binaries.executable {
            entryPoint = "main"
        }
    }
    linuxX64 {
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

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":oci-fetch-lib"))
            implementation(libs.clikt)
            implementation(libs.ktor.client.curl)
        }
    }
}
