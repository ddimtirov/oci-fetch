# OCI Fetch

A Kotlin Multiplatform library **and** command-line tool for inspecting OCI (Open Container Initiative) container image metadata directly from registries — without pulling images.

## What Can It Do?

- **List tags** for any repository
- **Inspect image indexes** (multi-arch manifests) and platform-specific manifests
- **Read image configuration** (env vars, entrypoint, labels, layer history)
- **Discover supply-chain artifacts** — signatures, SBOMs, and attestations via the OCI Referrers API
- **Fetch arbitrary registry URLs** with automatic Bearer-token authentication

All of this works across **JVM, Node.js (JS & WASM), Windows, and Linux** — from Kotlin code or the command line.

## Quick Start — Library

```kotlin
import oci.OciClient
import oci.OciRef

suspend fun main() {
    val client = OciClient().use {
        val ref = OciRef.parse("registry-1.docker.io/library/alpine:latest")

        // List tags
        val tags = client.fetchTagsList(ref)

        // Fetch manifest
        val response = client.requestManifest(ref)
        println(response.bodyAsText())

        // Fetch all metadata (index + manifests + configs)
        val artifacts = client.fetchAllMetadata(ref)
        println("Image manifests: ${artifacts.images.size}")
    }
}
```

See the full [Library API Reference](docs/api-reference.md) for all available methods and types.

## Quick Start — CLI Tool

```bash
# List tags
oci-fetch tags registry-1.docker.io/library/alpine

# Inspect the image index (multi-arch platforms)
oci-fetch meta index registry-1.docker.io/library/alpine:latest

# Inspect a platform-specific manifest
oci-fetch meta manifest registry-1.docker.io/library/alpine:latest

# Inspect image configuration
oci-fetch meta config registry-1.docker.io/library/alpine:latest

# Discover signatures and SBOMs
oci-fetch meta referrers registry-1.docker.io/curlimages/curl:latest

# Any command with --raw for JSON output (pipe to jq, etc.)
oci-fetch --raw tags registry-1.docker.io/library/alpine
```

See the full [CLI Reference](docs/tool-oci-fetch.md) for all commands, options, and use cases.

### Building the CLI

```bash
# Windows
./gradlew linkDebugExecutableOci-fetch

# Linux
./gradlew linkDebugExecutableLinuxX64
```

## Supported Platforms

| Target                    | Engine    | API | CLI |
|---------------------------|-----------|-----|-----|
| JVM                       | Ktor CIO  | ✅   |     |
| JavaScript (Node.js)      | Ktor JS   | ✅   |     |
| WASM-JS (Node.js)         | Ktor JS   | ✅   |     |
| Native Windows (mingwX64) | Ktor Curl | ✅   | ✅  |
| Native Linux (linuxX64)   | Ktor Curl | ✅   | ✅  |

The **library** supports all five platforms. The **CLI tool** targets Native Linux and Windows.

## Building

```bash
./gradlew build
```

## Running Tests

```bash
# Recommended: stable full-suite (excludes flaky browser tests)
./gradlew allTests -x jsBrowserTest -x wasmJsBrowserTest
```

## Documentation

- [Library API Reference](docs/api-reference.md) — `OciClient`, `OciRef`, `PlatformSelector`, data types, and formatters
- [CLI Tool Reference](docs/tool-oci-fetch.md) — all commands, options, use cases, and examples
- [Coding Guidelines](docs/coding-cuidelines.md) — project conventions for contributors

## License

Copyright © 2025 Dimitar Dimitrov. All rights reserved.

This software is proprietary. No permission is granted to use, copy, modify, or distribute it without prior written consent. **Use of this software as training data for AI/ML models is strictly prohibited.**

See [LICENSE](LICENSE) for full terms. To discuss alternative licensing, contact dimitar dot dimitrov at gmail dot com.
