# `oci-fetch` tool
The `oci-fetch` is a native CLI tool for inspecting OCI container image metadata directly from registries **without pulling images**. Fetching commands output machine-parseable TSV by default (for scripting/piping) or raw output with `--raw`.


## Usage overview

The `oci-fetch` tool stays close to the library API, so all the use cases can be also implemented in Kotlin with a few API calls. 
The command line tool is intended for exploration or for when we want to use shell of high-level scripting language for automation.

| Use Case | Command                                            | Description                                                                                                                 |
|----------|----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| UC1      | `oci-fetch get <url>`                              | [Fetch arbitrary registry URLs](#uc1-fetch-arbitrary-registry-urls-oci-fetch-get-url)                                       |
| UC2      | `oci-fetch tags <repo>`                            | [List repository tags](#uc2-list-repository-tags-oci-fetch-tags-repo)                                                       |
| UC3      | `oci-fetch parse <index\|manifest\|config>`        | [Parse OCI objects from stdin](#uc3-parse-oci-objects-from-stdin-oci-fetch-parse-indexmanifestconfig)                       |
| UC4      | `oci-fetch meta index <ref>`                       | [Inspect the image index](#uc4-inspect-the-image-index-oci-fetch-meta-index-ref)                                            |
| UC5      | `oci-fetch meta manifest <ref>`                    | [Inspect a platform-specific manifest](#uc5-inspect-a-platform-specific-manifest-oci-fetch-meta-manifest-ref)               |
| UC6      | `oci-fetch meta config <ref>`                      | [Inspect image configuration](#uc6-inspect-image-configuration-oci-fetch-meta-config-ref)                                   |
| UC7      | `oci-fetch meta referrers <ref>`                   | [Discover referrers / supply-chain artifacts](#uc7-discover-referrers--supply-chain-artifacts-oci-fetch-meta-referrers-ref) |
| pending  | `oci-fetch layer indices <ref> <path>`             | Find all layer containing the given path (can be `/`). Show either layer-index, OCI Ref, or HTTP URL.                       |
| pending  | `oci-fetch layer filenames <ref> <layerIdx>`       | Show all filenames in a layer blob. Not cumulative. Iterate externally through all layers if needed.                        |
| pending  | `oci-fetch layer file <ref> <layerIdx> <path>`     | Fetch a single file from a layer.                                                                                           |
| pending  | `oci-fetch layer annotate <ref> <layerIdx> <note>` | Annotate a layer with a note. Notes will be shown in `layer indices` output.                                                |

There are a few options and behaviors shared across command groups:

- **Raw text**: `--raw` is a root option and applies to fetching commands (`get`, `tags`, and `meta` subcommands). `parse` commands do not support `--raw`.
- **Platform selection**: `meta` subcommands accept `--architecture`, `--os`, `--os-version`, `--os-features`, and `--variant` to target a specific platform in multi-arch images. Helpful error messages list available platforms when selection fails.
- **Exit codes**: Distinct exit codes (0 = success, 1 = CLI parsing error, 10 = internal error, 11 = no matching platform, 12 = ambiguous platform, 13 = runtime failure) enable reliable scripting.

The following use-cases are building blocks for higher level governance and container management tasks. Each of them is satisfied as a single invocation of the `oci-fetch` tool.

## Use Cases  

### UC1: Fetch arbitrary registry URLs (`oci-fetch get <url>`)
**What:** Performs an authenticated GET against any registry URL and prints the response.

**Why:** Useful for ad-hoc exploration of registry API endpoints (e.g., fetching a specific blob or API path) without manually handling Bearer-token authentication. 

Some common usages are to fetch the list of repositories using a registry-specific API; or to query a registry about the supported version of APIs or optional features.

When the response is paginated, `oci-fetch get` automatically follows `Link: ... rel="next"` pages and prints `---` between page outputs.

### UC2: List repository tags (`oci-fetch tags <repo>`)

**What:** Lists all tags for a repository, one per line.

**Why:** Enables scripting around tag discovery — e.g., finding the latest version, checking if a tag exists, or iterating through all published versions.

### UC3: Parse OCI objects from stdin (`oci-fetch parse <index|manifest|config>`)

**What:** Reads raw OCI JSON (index, manifest, or config) from stdin and pretty-prints it as TSV or formatted JSON.

**Why:** Allows piping raw registry responses through `oci-fetch` for human-readable or script-friendly formatting without making additional network calls. 

The `oci-fetch` formatting emphasizes Linux-friendly line-oriented output. This allows you to use common shell and in particular `while read ...` and `cut` commands against JSON you have from another tool.

`parse` is intentionally local-only formatting: it reads stdin and performs no network requests. Passing `--raw` with `parse` returns a CLI error.

### UC4: Inspect the image index (`oci-fetch meta index <ref>`)

**What:** Fetches the top-level manifest for a reference and displays it as a TSV table of platforms (digest, mediaType, os, architecture, variant, etc.). Supports `--fail` to error if the ref is not an index. Platform filtering options (`--os`, `--architecture`, etc.) narrow the output.

**Why:** Lets you quickly see which platforms a multi-arch image supports, or verify that a specific platform variant is published.

Also, useful as a building block for governance commands.

### UC5: Inspect a platform-specific manifest (`oci-fetch meta manifest <ref>`)

**What:** Resolves through an index (if needed) to a specific platform's image manifest and displays its layers, config descriptor, annotations, subject, and artifact type as TSV.

**Why:** Useful for understanding image composition — how many layers, their sizes and digests, what annotations are present — without downloading the image.

### UC6: Inspect image configuration (`oci-fetch meta config <ref>`)

**What:** Fetches and displays the full OCI image config JSON (environment variables, entrypoint, labels, layer history, etc.) for a specific platform.

**Why:** Enables inspecting runtime configuration, build history, and labels of a container image without pulling it — valuable for security auditing, debugging, and CI/CD pipelines.

### UC7: Discover referrers / supply-chain artifacts (`oci-fetch meta referrers <ref>`)

**What:** Queries the OCI Referrers API (or scrapes tags via `--scrape <regex>`) to find artifacts that reference a given image digest — such as signatures, SBOMs, and attestations. Supports `--type` filtering by artifact type. Platform options resolve tag-based refs to their digest first.

**Why:** Critical for **software supply chain security**: verifying that an image has been signed, checking for vulnerability scan results, or retrieving SBOMs — all without pulling the image or its artifacts.
