import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

tasks.register<Exec>("checkWslOpenSSL") {
    group = "verification"
    description = "Checks if OpenSSL development libraries are installed in WSL"

    commandLine("wsl", "bash", "-c", "dpkg -l | grep -q libssl-dev && dpkg -l | grep -q libcrypto++-dev")
    isIgnoreExitValue = true

    doLast {
        if (executionResult.get().exitValue != 0) {
            throw GradleException(
                """
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
            """.trimMargin(),
            )
        }
    }
}

tasks.register<Exec>("installMingwOpenSSL") {
    group = "build setup"
    description = "Installs OpenSSL for MinGW-w64 (no sudo required)"

    onlyIf { isWindows }

    val msys2Paths = listOf(
        "C:\\msys64\\usr\\bin\\pacman.exe",
        "C:\\msys32\\usr\\bin\\pacman.exe",
        System.getenv("MSYS2_ROOT")?.let { "$it\\usr\\bin\\pacman.exe" },
    ).filterNotNull()

    val pacmanPath = msys2Paths.firstOrNull { file(it).exists() }

    doFirst {
        if (pacmanPath == null) {
            throw GradleException(
                """
                |
                |MSYS2 not found. Please install MSYS2 from https://www.msys2.org/
                |
                |After installing MSYS2, run this task again or install OpenSSL manually:
                |  pacman -S mingw-w64-x86_64-openssl
                |
            """.trimMargin(),
            )
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

    val wslPath = testExecutable.absolutePath
        .replace("\\", "/")
        .replace(Regex("^([A-Z]):/", RegexOption.IGNORE_CASE)) { "/mnt/${it.value.lowercase()}" }

    commandLine("wsl", "bash", "-c", wslPath)
}

tasks.named("compileKotlinLinuxX64") {
    if (isWindows) {
        dependsOn("checkWslOpenSSL")
    }
}

tasks.named("compileKotlinMingwX64") {
    dependsOn("installMingwOpenSSL")
}