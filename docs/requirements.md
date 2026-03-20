`oci-fetch` is a kotlin library with the following API:

- `ociSetCredentials(registry: String, user: String, pass: String): Unit`
- `ociFetchManifest(image: String): Manifest`
- `IndexManifest.platforms: Set<OciPlatform> get()`
- `IndexManifest.fetchPlatformManifest(platform: OciPlatform)`
- `ImageManifest.fetchConfig(): JsonObject`
- `Manifest.fetchBlob(sha256sum: String): InputStream`

Non-functional requirements:

- handle all common Docker and OCI MIME types and JSON variances 
- handle all common anonymous authentications as instructed by the `WWW-Authenticate` headers sent by the registry 
- allow specifying credentials per registry

Use the following registries for test cases:

```
registry.access.redhat.com/ubi7/ubi
registry.access.redhat.com/ubi8/ubi
registry.access.redhat.com/ubi8/ubi-micro

registry.access.redhat.com/ubi9/ubi-init
registry-1.docker.io/library/memcached
registry-1.docker.io/library/nginx
registry-1.docker.io/library/busybox
registry-1.docker.io/library/alpine
registry-1.docker.io/library/ubuntu
```

For each of these:
* fetch all tags
* fetch the manifests for the first and the last tags
* if the manifest is an index manifest, fetch recursively all referenced manifests and finally all the associated configs.

Use anonymous access (for Docker Hub you need to authenticate as anonymous, possibly for Red Hat Quay too). 