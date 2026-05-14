# Layer functionality
* [ ] Implement: `oci-fetch layer indices <ociImageRef> [<layer-file-path>]`
* [ ] Implement: `oci-fetch layer filenames <ociImageRef> <layer-offset-or-digest>`
* [ ] Implement: `oci-fetch layer file <ociImageRef> <layer-offset-or-digest> <layer-file-path>`
* [ ] Implement: `oci-fetch layer annotate <ociImageRef> [--path <layer-file-path>] <layer-offset-or-digest> <note>`

# Persistent cache management
* [ ] Implement: local storage for creds and responses
* [ ] Implement: `oci-fetch caches stats`
* [ ] Implement: `oci-fetch caches purge credentials --min-age <seconds> --domain <domain>`
* [ ] Implement: `oci-fetch caches purge manifests --min-size <bytes> --min-age <seconds> <ociImageRefs>...`
* [ ] Implement: `oci-fetch caches purge blobs --min-size <bytes> --min-age <seconds> <ociImageRefs>...`

# Future
* [ ] Split `oci-fetch-cli` `oci-fetch-lib` into separate module
* [ ] Port OCI Scrape to KMP
* [ ] Implement RocksDB or SQLite storage for staging
* [ ] Implement analytics 

 