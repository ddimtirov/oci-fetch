plugins {
    kotlin("multiplatform") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

group = "io.github.ddimtirov"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
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
        nodejs() // FIXME: use Node 25
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
                    freeCompilerArgs.add("-Xexpect-actual-classes")  // suppressing incubation warning
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core:3.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-java:3.1.0")
            implementation("ch.qos.logback:logback-classic:1.5.16")
        }
        jvmTest.dependencies {
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
        jsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.1.0")
        }
        wasmJsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.1.0")
        }
        nativeMain.dependencies {
            implementation("io.ktor:ktor-client-curl:3.1.0")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runExample") {
    group = "application"
    description = "Runs the JVM example that fetches JSON from an HTTP URL."
    dependsOn("jvmJar")
    classpath = files(tasks.named<Jar>("jvmJar").get().archiveFile) + configurations["jvmRuntimeClasspath"]
    mainClass = "example.FetchJsonKt"
    project.findProperty("url")?.let { systemProperties["url"] = it as String? }
}

tasks.register<Exec>("checkWslOpenSSL") {
    group = "verification"
    description = "Checks if OpenSSL development libraries are installed in WSL"
    
    commandLine("wsl", "bash", "-c", "dpkg -l | grep -q libssl-dev && dpkg -l | grep -q libcrypto++-dev")
    isIgnoreExitValue = true
    
    doLast {
        if (executionResult.get().exitValue != 0) {
            throw GradleException("""
                |
                |OpenSSL development libraries are not installed in WSL.
                |
                |Please install them manually:
                |
                |  wsl sudo apt-get update
                |  wsl sudo apt-get install -y libssl-dev libcrypto++-dev
                |
                |Or for other distributions:
                |  - Fedora/RHEL: wsl sudo dnf install openssl-devel
                |  - Arch: wsl sudo pacman -S openssl
                |
            """.trimMargin())
        }
    }
}

tasks.register<Exec>("installMingwOpenSSL") {
    group = "build setup"
    description = "Installs OpenSSL for MinGW-w64 (no sudo required)"
    
    onlyIf { System.getProperty("os.name").lowercase().contains("windows") }
    
    // Check if MSYS2 is available
    val msys2Paths = listOf(
        "C:\\msys64\\usr\\bin\\pacman.exe",
        "C:\\msys32\\usr\\bin\\pacman.exe",
        System.getenv("MSYS2_ROOT")?.let { "$it\\usr\\bin\\pacman.exe" }
    ).filterNotNull()
    
    val pacmanPath = msys2Paths.firstOrNull { file(it).exists() }
    
    doFirst {
        if (pacmanPath == null) {
            throw GradleException("""
                |
                |MSYS2 not found. Please install MSYS2 from https://www.msys2.org/
                |
                |After installing MSYS2, run this task again or install OpenSSL manually:
                |  pacman -S mingw-w64-x86_64-openssl
                |
            """.trimMargin())
        }
        
        println("Installing OpenSSL for MinGW-w64...")
    }
    
    if (pacmanPath != null) {
        commandLine(pacmanPath, "-S", "--noconfirm", "--needed", "mingw-w64-x86_64-openssl")
    }
    
    isIgnoreExitValue = true
}

tasks.register<Exec>("linuxX64TestInWSL") {
    group = "verification"
    description = "Runs Linux native tests via WSL (default distribution)"
    dependsOn("linuxX64Test")
    
    val testExecutable = layout.buildDirectory.file("bin/linuxX64/debugTest/test.kexe").get().asFile
    
    doFirst {
        if (!testExecutable.exists()) {
            throw GradleException("Test executable not found: ${testExecutable.absolutePath}")
        }
    }
    
    // Convert Windows path to WSL path
    val wslPath = testExecutable.absolutePath
        .replace("\\", "/")
        .replace("C:", "/mnt/c")
        .replace("D:", "/mnt/d")
        .replace("E:", "/mnt/e")
    
    commandLine("wsl", "bash", "-c", wslPath)
}

// Check for WSL OpenSSL before Linux native compilation on Windows
tasks.named("compileKotlinLinuxX64") {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        dependsOn("checkWslOpenSSL")
    }
}

// Auto-install OpenSSL before Windows native compilation
tasks.named("compileKotlinMingwX64") {
    dependsOn("installMingwOpenSSL")
}
