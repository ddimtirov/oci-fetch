plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
}

repositories {
    mavenCentral()
}

java {
    toolchain{
        languageVersion = JavaLanguageVersion.of(25)
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>("kotlin") {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

extensions.configure<dev.detekt.gradle.extensions.DetektExtension>("detekt") {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.files("detekt.yml"))
    basePath = layout.buildDirectory.dir("reports/detekt")
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    reports {
        sarif.required = true
        html.required = true
    }
}

tasks.register<Exec>("linuxX64TestInWSL") {
    group = "verification"
    description = "Runs Linux native tests via WSL (default distribution)"
    dependsOn("linuxX64Test")

    val testExecutable = layout.buildDirectory
        .file("bin/linuxX64/debugTest/test.kexe")
        .get().asFile.absolutePath
        .replace("\\", "/")

    commandLine("wsl", "bash", "-c", "\"$(wslpath $testExecutable)\"")
}

tasks.matching { it.name.lowercase().matches(Regex("^linuxx64.*|.*linuxx64$")) }.configureEach {
    onlyIf("KTOR-3965 ktor-client-curl has linking issues for cross-compilation") {
        !System.getProperty("os.name").lowercase().contains("windows")
    }
}
