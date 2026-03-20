// Dependency lockfile maintenance tasks.
tasks.register("resolveAndLockAll") {
    group = "help"
    description = "Resolves and locks all resolvable configurations."
    notCompatibleWithConfigurationCache("Filters configurations at execution time")

    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "Run with --write-locks to generate/update lockfiles."
        }
    }

    doLast {
        configurations
            .filter { it.isCanBeResolved }
            .forEach { it.resolve() }
    }
}