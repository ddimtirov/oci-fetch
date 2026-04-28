# Dependency Management Guidelines

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

## Error Handling

- Never replace an exception with `null` unless you can meaningfully recover through a fallback action. Replacing exceptions with `null` only propagates the error further away from the root cause.
