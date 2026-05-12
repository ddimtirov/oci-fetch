---
name: dependency-management
description: Manage project dependencies and refresh Gradle locks. 
             Use when touching dependencies in build files 
             (build.gradle.kts, settings.gradle.kts, libs.versions.toml) 
             or buildSrc.
---

# Dependency Management

To maintain consistent builds across environments, this project uses Gradle dependency locking.

## Dependency Updates

Whenever you modify dependencies in any of the following files:
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- Any `*.gradle.kts` files in `buildSrc`

You **MUST** refresh the dependency locks by running the following command:

```bash
./gradlew resolveAndLockAll --write-locks
```

This ensures that `gradle.lockfile` and `settings-gradle.lockfile` are updated to reflect the changes. Always include the updated lockfiles in your submission.

## Lock Drift Detection (KMP false platform errors)

If Gradle reports a multiplatform resolution error similar to:
- `Couldn't resolve dependency 'group:artifact-jvm:version' in 'commonMain' for all target platforms`
- `The dependency should target platforms: [...]`
- `Unresolved platforms: [...]`

treat it as a potential lock drift issue, even if the dependency declaration looks correct.

- `Couldn't resolve dependency 'group:artifact-jvm:version' in 'commonMain' for all target platforms`
- `The dependency should target platforms: [...]`
- `Unresolved platforms: [...]`

treat it as a potential lock drift issue, even if the dependency declaration looks correct.

In this project, this commonly happens when a root module lock entry (for example `io.ktor:ktor-client-auth:<version>`) is missing or stale in `gradle.lockfile`, while platform-specific entries (such as `-jvm`, `-js`, `-linuxx64`, etc.) exist or were partially updated.

When this symptom appears, you **MUST** refresh locks first:

```bash
./gradlew resolveAndLockAll --write-locks
```

Then re-run the failing compile task (for example `compileCommonMainKotlinMetadata`) to verify resolution succeeds before changing source code.

## Stable Test Suite

For a stable full-suite test run across all platforms (excluding unstable browser tests), use:
```bash
./gradlew allTests -x jsBrowserTest -x wasmJsBrowserTest
```
