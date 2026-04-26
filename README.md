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
./gradlew linkDebugExecutableOci-fetch     # Windows native executable
./gradlew linkDebugExecutableLinuxX64     # Linux native executable
```

## Command-Line Tool (oci-fetch)

The project includes a native command-line tool `oci-fetch` for interacting with OCI registries.

### Building the CLI

To build the native executable for your platform:

```bash
# Windows
./gradlew linkDebugExecutableOci-fetch

# Linux
./gradlew linkDebugExecutableLinuxX64
```

The executable will be located at:
- Windows: `build/bin/oci-fetch/debugExecutable/oci-fetch.exe`
- Linux: `build/bin/linuxX64/debugExecutable/oci-fetch.kexe`

### CLI Usage

```bash
# List tags for a repository
oci-fetch tags registry-1.docker.io/library/alpine

# Get raw JSON response for tags
oci-fetch --raw tags registry-1.docker.io/library/alpine

# Show help
oci-fetch --help
oci-fetch tags --help
```

### Referrers, Signatures, and SBOMs

The `meta referrers` command can be used to discover artifacts associated with an image, such as Cosign signatures, Notary signatures, or SBOMs.

```bash
# Find signatures for curl on Docker Hub
oci-fetch meta referrers registry-1.docker.io/curlimages/curl:latest

# Find signatures for .NET runtime on MCR
oci-fetch meta referrers mcr.microsoft.com/dotnet/runtime:8.0

# Find referrers (signatures and metadata) on GHCR
oci-fetch meta referrers ghcr.io/stefanprodan/podinfo:latest

# Find Red Hat UBI10 Minimal Cosign signatures (requires platform digest)
# 1. Find the digest for your architecture (e.g., amd64)
oci-fetch meta index registry.access.redhat.com/ubi10/ubi-minimal:latest
# 2. Fetch referrers using the specific digest
oci-fetch meta referrers registry.access.redhat.com/ubi10/ubi-minimal@sha256:b01cbd6a4c538ea1002ca774d5e09adcae15a1c095804400ea201ea5811431b1

# Get raw OCI Index of referrers
oci-fetch --raw meta referrers registry-1.docker.io/curlimages/curl:latest
```

Example output for `curlimages/curl:latest`:
```text
digest  artifactType    mediaType       size    annotations
sha256:562fd503... application/vnd.dev.sigstore.bundle.v0.3+json   application/vnd.oci.image.manifest.v1+json      894     dev.sigstore.bundle.content=dsse-envelope; ...
sha256:c7ba3d0b... application/vnd.dev.sigstore.bundle.v0.3+json   application/vnd.oci.image.manifest.v1+json      894     dev.sigstore.bundle.content=dsse-envelope; ...
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
