import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

extensions.configure<KotlinMultiplatformExtension>("kotlin") {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    // FIXME: Remove when Clikt fixes the issue. See https://kotl.in/disable-native-cache
    targets.withType(KotlinNativeTarget::class.java).matching { it.name == "linuxX64" }.configureEach {
        @OptIn(KotlinNativeCacheApi::class)
        binaries.all {
            disableNativeCache(
                version = DisableCacheInKotlinVersion.`2_4_0`,
                reason = "Workaround for Clikt 5.x duplicate symbol linker error (selfAndAncestors)."
            )
        }
    }
}
