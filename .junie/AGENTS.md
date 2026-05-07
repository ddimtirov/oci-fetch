# oci-fetch Agent Instructions

This document outlines the general knowledge and guidelines for developing and maintaining the `oci-fetch` library. These instructions should be followed beyond specific tasks.

## 1. Dependency Management

To maintain consistent builds across environments, this project uses Gradle dependency locking.

### Dependency Updates

Whenever asked to refresh dependencies, or you modify dependencies in any of the following files:
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- Any `*.gradle.kts` files in `buildSrc`

You **MUST** refresh the dependency locks by running the following command:

```bash
./gradlew resolveAndLockAll --write-locks
```

This ensures that `gradle.lockfile` and `settings-gradle.lockfile` are updated to reflect the changes. Always include the updated lockfiles in your submission.

## 2. API Design and Usability

- **Documentation:** Create KDoc documentation only when the intent is not obvious from the code.
- **Kotlin Idioms:** Finalize API design following standard Kotlin idioms for a clean and intuitive surface.
- Use `xxx?.let { ... }` in preference to `if (xxx != null) { ... }`, unless you have an `else` clause.

## 3. Error Handling

- **No Null for Exceptions:** Never replace an exception with `null` unless you can meaningfully recover through a fallback action. Replacing exceptions with `null` only propagates the error further away from the root cause.
- **Propagation:** Let exceptions bubble up to the caller rather than swallowing them.
- **Assertions:** Use assertions, `check()`, `require()` and `requireNotNull()` to check pre/post-conditions and invariants.
- **Informative Errors:** Implement detailed error messages with troubleshooting hints to help diagnose and fix issues quickly.
- API functions should not print – return a result or throw exception. Error callbacks can be used, but only when they really add value.
- Application should print payload to stdout and diagnostics to stderr.
- **Resilience:** Add retry mechanisms for transient failures (e.g., network issues).
- **Observability:** Provide logging hooks for debugging and monitoring.

## 4. Testing and Validation

- In addition to code coverage, aim for and maintain high use-case coverage – each use case should have acceptance test(s).

### Unit Testing
- Implement tests for all public API functions.
- Add tests specifically for error conditions and edge cases.
- Use mocks to inject errors.
- When mocking to avoid external dependencies (as opposed to injecting an error), ensure we have an integration test confirming the mocked behavior.

### Integration Testing
- Set up and run tests against specified real registries (e.g., Red Hat, Docker Hub).
- Validate core workflows:
    - Tag fetching.
    - Manifest retrieval for various image types.
    - Recursive manifest and config fetching.

## 5. Command Line Application

- Use the command line application as a reference implementation.
- Ensure it provides comprehensive examples for each registry type and common workflows (tag listing, manifest inspection).
- Document examples with explanatory comments.

## 6. Platform and Testing Environment

- **Supported Platforms:** The library supports JVM, JavaScript (Node.js), WASM-JS (Node.js), Native Windows (mingwX64), and Native Linux (linuxX64). The application targets Native Linux and Windows.
- **Stable Test Suite:** For a stable full-suite test run across all platforms (excluding unstable browser tests), use:
  ```bash
  ./gradlew allTests -x jsBrowserTest -x wasmJsBrowserTest
  ```
- **Native Implementation:** Native platforms use the Curl Ktor HTTP engine, as CIO does not have support for TLS 1.3.
- **Browser Limitations:** JavaScript and WASM-JS browser tests fail due to CORS or networking issues when accessing external registries. These should be considered when adding new integration tests.
