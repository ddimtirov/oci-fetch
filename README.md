# OCI Fetch - Multiplatform OCI Registry Client

A Kotlin Multiplatform library for fetching OCI (Open Container Initiative) image manifests from container registries.

## Supported Platforms

- **JVM** - Uses Java HTTP client (✅ Tested & Working)
- **JavaScript** - Uses JS fetch API (✅ Tested & Working in Node.js)
- **WASM-JS** - Uses JS fetch API (✅ Tested & Working in Node.js)
- **Native Windows (mingwX64)** - ❌ Fails to link on current Windows environment (Missing MSYS2/OpenSSL)
- **Native Linux (linuxX64)** - ❌ Fails to link on current Windows environment (Toolchain/OpenSSL issues)

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
- ✅ **Fully working platforms**: JVM, JavaScript (Node.js), WASM (Node.js)
- ❌ **Failing platforms**: Native Windows (mingwX64), Native Linux (linuxX64) - see linking issues below.
- ⚠️ Browser tests have CORS/networking issues when accessing external registries
- 💡 Use `./gradlew allTests -x mingwX64Test -x linuxX64Test -x jsBrowserTest -x wasmJsBrowserTest` for the stable full-suite entrypoint

### Unfixed Failures (Verified 2026-03-20)

- **Native Linking Errors**: Both `mingwX64` and `linuxX64` targets fail to link on the current Windows environment.
  - `mingwX64`: Fails because MSYS2 is not found (required for `pacman` to install OpenSSL).
  - `linuxX64`: Fails with `undefined reference to 'EVP_MD_CTX_new'` and similar OpenSSL symbols. The Kotlin Native cross-compiler on Windows fails to correctly link against WSL's `libssl`.

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
  - JVM: `io.ktor:ktor-client-java`
  - JS/WASM: `io.ktor:ktor-client-js`
  - Native: `io.ktor:ktor-client-curl`

## Known Limitations

### Native Platforms (Windows/Linux)

Native platforms use the **Curl engine** which requires OpenSSL libraries.

#### Windows (mingwX64)

**Automatic Installation**: OpenSSL for MinGW-w64 is **automatically installed** via MSYS2 pacman during Windows native builds (no sudo/elevation required).

**Prerequisites**: [MSYS2](https://www.msys2.org/) must be installed.

The build will automatically:
1. Detect MSYS2 installation
2. Install `mingw-w64-x86_64-openssl` package if needed
3. Proceed with compilation

If MSYS2 is not found, you'll get an error with installation instructions.

**Manual installation**:
```bash
# In MSYS2 terminal
pacman -S mingw-w64-x86_64-openssl
```

#### Linux (linuxX64) on Windows via WSL

**Manual Installation Required**: The build will check for OpenSSL libraries and fail with instructions if missing.

Install OpenSSL in WSL:
```bash
wsl sudo apt-get update
wsl sudo apt-get install -y libssl-dev libcrypto++-dev
```

Or for other distributions:
```bash
# Fedora/RHEL
wsl sudo dnf install openssl-devel

# Arch
wsl sudo pacman -S openssl
```

#### Linux (linuxX64) on Native Linux

Install OpenSSL development libraries:
```bash
# Ubuntu/Debian
sudo apt-get install libssl-dev libcrypto++-dev

# Fedora/RHEL
sudo dnf install openssl-devel

# Arch
sudo pacman -S openssl
```

### Browser Tests

Browser tests require Chrome/Chromium for headless testing and may have CORS/networking issues when accessing external registries.

## License

[Your License Here]
