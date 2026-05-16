// Dependency lockfile maintenance tasks.
val resolveAndLockAll = tasks.register("resolveAndLockAll") {
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

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    resolveAndLockAll {
        dependsOn(tasks.named("kotlinUpgradeYarnLock"))
    }
}