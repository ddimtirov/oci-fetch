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
