# OCI Fetch - Multiplatform OCI Registry Client

A Kotlin Multiplatform library for fetching OCI (Open Container Initiative) image manifests from container registries.

## Supported Platforms

- **JVM** - Uses Java HTTP client
- **JavaScript** - Uses JS fetch API (Node.js and Browser)
- **WASM-JS** - Uses JS fetch API (Browser and Node.js)
- **Native Windows (mingwX64)** - Uses libcurl
- **Native Linux (linuxX64)** - Uses libcurl

## Running Tests

| Platform | Command | Test Report | Status |
|----------|---------|-------------|--------|
| **JVM** | `./gradlew jvmTest` | `build/reports/tests/jvmTest/index.html` | ✅ Working |
| **JS (Node.js)** | `./gradlew jsNodeTest` | `build/reports/tests/jsNodeTest/index.html` | ✅ Working |
| **JS (Browser)** | `./gradlew jsBrowserTest` | `build/reports/tests/jsBrowserTest/index.html` | ⚠️ Some tests fail |
| **JS (Both)** | `./gradlew jsTest` | `build/reports/tests/jsTest/index.html` | ✅ Working |
| **WASM (Node.js)** | `./gradlew wasmJsNodeTest` | `build/reports/tests/wasmJsNodeTest/index.html` | ✅ Working |
| **WASM (Browser)** | `./gradlew wasmJsBrowserTest` | `build/reports/tests/wasmJsBrowserTest/index.html` | ⚠️ Some tests fail |
| **WASM (Both)** | `./gradlew wasmJsTest` | `build/reports/tests/wasmJsTest/index.html` | ✅ Working |
| **Windows Native** | `./gradlew mingwX64Test` | `build/reports/tests/mingwX64Test/index.html` | ✅ Auto-installs deps |
| **Linux Native** | `./gradlew linuxX64Test` | `build/reports/tests/linuxX64Test/index.html` | ⚠️ Manual install |
| **Linux (via WSL)** | `./gradlew linuxX64TestInWSL` | `build/reports/tests/linuxX64Test/index.html` | ⚠️ Manual install |
| **Working targets** | `./gradlew jvmTest jsNodeTest wasmJsNodeTest` | Multiple reports | ✅ Recommended |

**Notes:**
- ✅ **Fully working platforms**: JVM, JavaScript (Node.js), WASM (Node.js)
- ⚠️ Browser tests have CORS/networking issues when accessing external registries
- 💡 Use `./gradlew jvmTest jsNodeTest wasmJsNodeTest` to run all working tests

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
