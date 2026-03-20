# OCI Fetch - Multiplatform OCI Registry Client

A Kotlin Multiplatform library for fetching OCI (Open Container Initiative) image manifests from container registries.

## Supported Platforms

- **JVM** - Uses Ktor CIO engine (✅ Tested & Working)
- **JavaScript** - Uses Ktor JS fetch engine (✅ Tested & Working in Node.js)
- **WASM-JS** - Uses Ktor JS fetch engine (✅ Tested & Working in Node.js)
- **Native Windows (mingwX64)** - Uses Ktor CIO engine (✅ Tested & Working)
- **Native Linux (linuxX64)** - Uses Ktor CIO engine (✅ Tested & Working)

## Running Tests

| Platform              | Command                                                     | Test Report                                        | Notes                        |
|-----------------------|-------------------------------------------------------------|----------------------------------------------------|------------------------------|
| **All (recommended)** | `./gradlew allTests  -x jsBrowserTest -x wasmJsBrowserTest` | Multiple reports                                   | Runs stable subset           |
| **JVM**               | `./gradlew jvmTest`                                         | `build/reports/tests/jvmTest/index.html`           |                              |
| **JS (Node.js)**      | `./gradlew jsNodeTest`                                      | `build/reports/tests/jsNodeTest/index.html`        |                              |
| **JS (Browser)**      | `./gradlew jsBrowserTest`                                   | `build/reports/tests/jsBrowserTest/index.html`     | Some tests fail (CORS)       |
| **JS (Both)**         | `./gradlew jsTest`                                          | `build/reports/tests/jsTest/index.html`            |                              |
| **WASM (Node.js)**    | `./gradlew wasmJsNodeTest`                                  | `build/reports/tests/wasmJsNodeTest/index.html`    |                              |
| **WASM (Browser)**    | `./gradlew wasmJsBrowserTest`                               | `build/reports/tests/wasmJsBrowserTest/index.html` | Some tests fail (CORS)       |
| **WASM (Both)**       | `./gradlew wasmJsTest`                                      | `build/reports/tests/wasmJsTest/index.html`        |                              |
| **Windows Native**    | `./gradlew mingwX64Test`                                    | `build/reports/tests/mingwX64Test/index.html`      |                              |
| **Linux Native**      | `./gradlew linuxX64Test`                                    | `build/reports/tests/linuxX64Test/index.html`      | Skipped on Windows (use WSL) |
| **Linux (via WSL)**   | `./gradlew linuxX64TestInWSL`                               | `build/reports/tests/linuxX64Test/index.html`      | May fail and prompt install  |

**Notes:**
- ✅ **Fully working platforms**: JVM, JavaScript (Node.js), WASM (Node.js), Native Windows (mingwX64), Native Linux (linuxX64)
- ⚠️ Browser tests have CORS/networking issues when accessing external registries
- 💡 Use `./gradlew allTests -x jsBrowserTest -x wasmJsBrowserTest` for the stable full-suite entrypoint

## Building

Build all targets:
```bash
./gradlew build
```

## Dependency Locking

Generate or refresh lockfiles for all resolvable Gradle configurations:
```bash
./gradlew resolveAndLockAll --write-locks
```

This updates `gradle.lockfile` (project dependencies) and `settings-gradle.lockfile` (settings/plugin dependencies).

Build specific targets:
```bash
./gradlew jvmJar           # JVM JAR
./gradlew jsJar            # JavaScript artifact
./gradlew wasmJsJar        # WASM artifact
./gradlew linkDebugExecutableMingwX64   # Windows native executable
./gradlew linkDebugExecutableLinuxX64   # Linux native executable
```

## Usage Example

```kotlin
import oci.OciClient

suspend fun main() {
    val client = OciClient()
    try {
        // Parse image reference
        val ref = OciClient.parseRef("registry-1.docker.io/library/alpine:latest")

        // Fetch manifest
        val response = client.fetchManifest(ref)
        println(response.bodyAsText())

        // Fetch all artifacts (manifest + configs)
        val artifacts = client.fetchArtifacts(ref)
        println("List manifest: ${artifacts.listManifest}")
        println("Image manifests: ${artifacts.imageManifests.size}")
        println("Configs: ${artifacts.configs.size}")
    } finally {
        client.close()
    }
}
```

## Architecture

The library uses Kotlin Multiplatform's expect/actual mechanism:
- **Common code** (`commonMain`) contains shared business logic
- **Platform-specific code** provides HTTP client implementations:
  - JVM: `io.ktor:ktor-client-cio`
  - JS/WASM: `io.ktor:ktor-client-js`
  - Native: `io.ktor:ktor-client-cio`

## Known Limitations

### Native Platforms (Windows/Linux)

Native platforms use the **CIO engine** which is written in Kotlin and has no external dependencies. No additional prerequisites (like OpenSSL or MSYS2) are needed beyond a standard Kotlin Native toolchain.

### Browser Tests

Browser tests require Chrome/Chromium for headless testing and may have CORS/networking issues when accessing external registries.

## License

[Your License Here]
