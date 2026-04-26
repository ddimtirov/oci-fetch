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

