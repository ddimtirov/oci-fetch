# Library API Reference

The `oci-fetch` library is a Kotlin Multiplatform SDK for interacting with OCI (Open Container Initiative) container registries. It builds on Ktor's `HttpClient` and implements container handling logic for OCI-compliant registries.

## `OciClient`

**Responsibility:** The main entry point for all registry interactions. Performs authenticated HTTP requests against OCI-compliant registries, transparently handling anonymous Bearer-token authentication challenges.

**API:**
- `requestUrl()` — GET any registry URL with automatic auth. Useful for fetching non-standard requests, such as fetching the registry catalog.
- `requestBlob()` — fetch a blob by digest.
- `requestTags()` — fetch the raw tags-list response.
- `requestManifest()` — fetch an image manifest or index.
- `fetchAllTags()` — return parsed `List<String>` of tags.
- `requestRepositoriesDocker()` — fetch the raw response from the Docker Registry `_catalog` endpoint.
- `fetchAllRepositoriesDocker()` — return parsed `List<String>` of repository names from the Docker `_catalog` endpoint, handling pagination automatically.
- `fetchAllMetadata()` — recursively fetch all manifests and configs for an image reference.
- `fetchReferrers()` — query the OCI Referrers API (with Cosign tag-schema fallback) for supply-chain artifacts referencing a digest.
- `scrapeReferrers()` — discover referrers by scanning tags matching a regex.
- `resolveToImageManifest()` — ensure that we have a reference pointing to an image manifest. If the initial reference points to an index, return a new reference pointing to a platform-specific image manifest by using the provided `PlatformSelector`.
- `isOciImageIndex()` / `isOciImageManifest()` — detect whether a JSON payload is an index or image manifest.

**Naming conventions:** `requestXXX()` methods return a raw Ktor `HttpResponse`; `fetchXXX()` methods handle HTTP details and return parsed/typed results; `isOciXXX()` methods detect payload types. The `xxxDocker` suffix indicates Docker-specific (non-OCI-standard) endpoints.

**Usage:** `OciClient` is an interface. Create via the companion factory `OciClient(httpClient?)`. Supply your own `HttpClient` to customize transport (proxies, timeouts, logging) — when provided, the caller manages its lifecycle.

## `OciRef`

**Responsibility:** An immutable, structured representation of an OCI image reference (registry + repository + tag-or-digest). Handles parsing of standard reference strings and provides convenience methods for switching between tag and digest forms.

**API:**
- `OciRef.parse()` — parse a string like `registry-1.docker.io/library/alpine:latest` or `…@sha256:…` into an `OciRef`.
- `withDigest()` / `withTag()` / `withReference()` — derive a new `OciRef` with a different reference component.
- Properties: `registry`, `repository`, `reference`, `isDigest`.

**Usage:** A data class — can be constructed directly or via `parse()`, and modified copies can be created via the `withXxx()` factory methods. All `OciClient` methods that accept registry/repository/tag strings also have overloads accepting `OciRef`.

`OciRef`'s `isDigest` property indicates whether the reference is a digest (true) or a tag (false). Tag refs always point to an index or image manifest, while digest refs always point to a blob (which may happen to be a manifest). We can convert from tag to digest, but not the other way around.

## `PlatformSelector`

**Responsibility:** Filters and matches OCI platform descriptors (architecture, OS, OS version, OS features, variant). Used to select a specific platform entry from a multi-arch image index.

**API:**
- Constructor accepts optional `architecture`, `os`, `osVersion`, `osFeatures`, `variant`.
- `matches()` — test whether a platform descriptor satisfies the selector's constraints.
- `hasConstraints()` — returns `true` if any filter is set.

**Usage:** Pass to `OciClient.resolveToImageManifest()` or use standalone for filtering index entries.

## `Platform`

**Responsibility:** A typed representation of an OCI platform descriptor extracted from an index manifest entry.

**API:**
- Properties: `arch`, `osName`, `variant?`, `osVersion?`, `osFeatures`.
- `Platform.fromJson()` — parse a platform from a JSON index entry.
- `isValid()` — returns `false` for unknown/placeholder platforms.

**Usage:** Thrown inside `NoSuchPlatformSelectionException` and `AmbiguousPlatformSelectionException` to report available or ambiguous platform choices.

## Data Types (`ImageIndexArtifacts`, `ImageArtifacts`, `ManifestResolution`)

**Responsibility:** Typed containers for fetched OCI metadata.

- `ImageIndexArtifacts(ref, index?, images)` — the result of `fetchAllMetadata()`: the optional top-level index JSON plus a list of per-platform `ImageArtifacts`.
- `ImageArtifacts(ref, manifest, config?)` — a single platform image's manifest and optional config JSON.

## Pagination (`HttpResponse.nextPageUrl`)

**Responsibility:** Extract the next-page URL from an OCI registry response, following the [OCI Distribution Spec pagination](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pagination) convention based on the `Link` header with `rel="next"`.

**API:**
- `HttpResponse.nextPageUrl(registry, v2endpoint)` — returns the absolute URL of the next page, or `null` when no further pages exist. Handles absolute URLs, absolute paths, relative `v2/` paths, and query-only links by normalising them against the given registry and endpoint.

**Usage:** Used internally by `OciClient` to transparently paginate tag lists, repository lists, and referrers responses. Can also be called directly on any `HttpResponse` obtained from an OCI registry endpoint.

## Formatters

**Responsibility:** Convert OCI JSON structures into human-readable or script-friendly output formats.

**API:**
- `formatTsvIndex(body, selector?)` — format an index manifest as a TSV table of platforms.
- `formatTsvManifest(manifestStr)` — format an image manifest as TSV (layers, config, annotations, subject).
- `formatTsvReferrers(indexStr)` — format a referrers index as TSV.
- `formatPrettyJson(json)` / `formatPrettyJson(str)` — pretty-print any JSON object or string.

**Usage:** Top-level functions in the `oci` package. Add new formatters following the same pattern for additional output formats.
