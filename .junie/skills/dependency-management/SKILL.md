---
name: dependency-management
description: Manage project dependencies and refresh Gradle locks. Use when adding, removing, or updating dependencies in build files (build.gradle.kts, settings.gradle.kts, libs.versions.toml) or buildSrc.
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

## Stable Test Suite

For a stable full-suite test run across all platforms (excluding unstable browser tests), use:
```bash
./gradlew allTests -x jsBrowserTest -x wasmJsBrowserTest
```
