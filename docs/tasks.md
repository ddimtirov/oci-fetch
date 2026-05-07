# Improve Testing
Add integration tests for attestation discovery 
* [ ] using the OCI Referrers API 
* [ ] using the Cosign tag schema (UBI images)
* [ ] using the In-Toto platforms schema (Alpine images)

# Caching and KTOR idioms
* [ ] Implement credentials caching using Ktor plugin
* [ ] Implement request body caching for ETag and Last-Modified headers using Ktor plugin
* [ ] Implement special handling of the content-addressed blob-caching as it is guaranteed immmutable
* [ ] Try to replace the Bearer toke auth with a Ktor plugin
                         
# Pagination and Non-OCI APIs 
* [ ] Support pagination for tags
* [ ] Add options to `oci get ...` to generically handle pagination 
* [ ] Support the Legacy Docker Registry catalog API (rejected by the OCI standard), https://distribution.github.io/distribution/spec/api/#catalog -- used by Nexus, Artifactory, and Harbor 

# Layer functionality
* [ ] Implement: `oci-fetch layer indices <ociImageRef> [<layer-file-path>]`
* [ ] Implement: `oci-fetch layer filenames <ociImageRef> <layer-offset-or-digest>`
* [ ] Implement: `oci-fetch layer file <ociImageRef> <layer-offset-or-digest> <layer-file-path>`
* [ ] Implement: `oci-fetch layer annotate <ociImageRef> [--path <layer-file-path>] <layer-offset-or-digest> <note>`

# Cache management functionality
* [ ] Implement: `oci-fetch caches stats`
* [ ] Implement: `oci-fetch caches purge manifests --min-size <bytes> --min-age <seconds> <ociImageRefs>...`
* [ ] Implement: `oci-fetch caches purge blobs --min-size <bytes> --min-age <seconds> <ociImageRefs>...`
