# `oci-scrape` tool

> `oci-scrape` is a companion tool to `oci-fetch`, focusing on higher level governance workflows involving multiple images and registries. It should be used as the data preparation and extraction feeding a governance data warehouse, but can also be used on its own for ad-hoc queries.
>
> It was implemented in JVM-Kotlin, and we will rebuild it using the `oci-fetch` library and include the use-cases in the `oci-fetch` tool.

`oci-scrape` is a command line tool that indexes container registries into easy-to-parse flat files.
It is designed to act as the initial stage of a pipeline that feeds an analytical database,
allowing us to generate reports for data not readily available using current tools.
See the [queries](#queries) and [data schema](#data-schema) sections for details.

Our initial motivation is to provide enough data to identify image inheritance and aliasing
(the practice of publishing the same content under multiple names).
While time data is inconsistent, we aim to provide good heuristics for time-based queries.

Any queries based on non-container metadata (attestations, signatures, etc.) are outside
this initial scope. Registry discovery is also out of scope, as it is not part of the standard
and in practice, the mechanisms are always registry-specific. We delegate the low-level registry
interactions to `skopeo`, which should be available through the path.

`oci-scrape` is a [feature, not a product][forbes-fnap].
However, since no existing product currently provides this functionality,
we are building it ourselves.
Ideally, once we have demonstrated the value of our use cases,
container registries will adopt these features and implement them in a more efficient way.

[forbes-fnap]: https://www.forbes.com/sites/victoriabarret/2011/10/18/dropbox-the-inside-story-of-techs-hottest-startup/



## Usage

The tool has four commands with functionality as follows:

- `register-repositories` is how we set new container repositories to be scanned at later stages.
  Discovering the repositories is non-standard functionality, and each registry provides different ways to do that.
  The example below shows usage of the DockerHub-specific `/v2/_catalog` endpoint.

- `scan` is the main command, discovering all tags for each repository and fetching
  the `OCI manifest` and `OCI config` data for each new image. We always assume
  multi-architecture, and treat single-architecture images as a list of one manifest.
  The `tags-update` option is important as it allows us to discover new tags,
  delete no-longer available tags, or re-scan tags that we have already scanned
  (allowing us to handle changing tags such as `latest`)
  The data schema retains all historical versions of the tags, along with when they were observed.

- `import` is an easy way to scan a single container image,
  automatically adding the registry and repository information if necessary.
  It is designed to be used with CI/CD.

- `info` print information about the generated data files.
  It is not intended to be a full-blown query tool, but we may add a few hardcoded reports.


```bash
# add new container repositories that will be scanned (one per line, from file or stdin)
oci-scrape register-repositories "$file"
curl https://mydockerhyb/v2/_catalog | jq -r '.registries[]' | oci-scrape register-repositories -

# discover new tags and fetch the metadata for them  (with `--tags-update merge`; default) 
# discover fetch the metadata for already discovered tags (with `--tags-update skip`) 
# update the metadata for already scanned repositories, i.e. to detect deletions, or handle `latest` tag  (with `--tags-update refresh`)
oci-scrape scan    [--batch-size 1] [--parallel 10] [--tags-update {merge|skip|refresh}] \
                   [--registries <registry-mask>] [--repositories <repository-mask>] [--tags <tag-mask>] \
                   [<image-mask>] 

# scan a specific single container image, registering the registry and repository if needed
oci-scrape import "$image" 

# print statistics about the data files
oci-scrape info [--registries <registry-mask>] [--repositories <repository-mask>] [--tags <tag-mask>] [<image-mask>] 
```

## Data schema
* Repositories in registries (single TOML)
* Tags in repositories
    * each registry is a zip
    * each repository is a CBOR file inside that zip, containing an array of tag records
    * each tag record keeps track of the current and past manifest hashes
    * the manifest hash in the tag can be empty, meaning that the tag is not scanned yet, or deleted
    * persisting tags appends them to the files; we need to opt in if we want to overwrite the existing tags
* Manifests and configs
    * each registry is a zip
    * each repository is a CBOR file inside that zip, containing an array of manifest-list records
    * each manifest-list keeps common metadata and list of image manifests and config objects
    * persisting manifests-lists appends them to the files; old versions remain, as tags will contain hashes pointing to them
    * in the future we may add garbage collection, deleting manifest-lists that are not referred-to by the current or past hashes of any tags


## Queries
- for an image tag, find the hash
- for an image hash, find all tags and repositories
- for an image hash, find all tags and repositories sharing the same layers, differing only in config
- for an image hash, find all tags and repositories with layers that are strict prefix (ancestor images), sort them by distance
- for an image hash, find all tags and repositories with layers that are strict extension (descendent images), arrange them in tree
- for a repository, find all descendents of all images in a repository. Report at repository or tag level
- all tags and hashes from repository ordered by time (best guess)
- for an image hash, find all newer images in the repository and order them by build time (heuristic)
- for an image hash, find all ancestor images that have newer tag than the subject
- for a duration value, find all images older than the latest tag of their ancestor by at least the subject value. Sort by outdated difference
- for 2 images, find the closest common ancestor
- for a set of images, build an inheritance tree
- for an integer value of N, find all repositories with more than N descendent repositories
- for a name and time filter, find all repositories containing unfiltered images descending from multiple repositories
- for a name and time filter, find all repositories containing unfiltered images descending from multiple repositories in interlaeved timeframes
- SBOM, attestations and content integrity checks, based on Referrers API, TUF, InToto, Vex, etc.

All image, repository, registry, and tag filters are globs by default, unless they start with `^` and end on `$`, in which case they are regexes.


## Out of scope

### Repository discovery within registry
- The OCI Distribution spec intentionally does not provide a way to discover repos in registries.
- `_catalog` is a non-standard implementation detail of Docker Hub. While it is emulated by Nexus, we cannot rely on it.
- Different registry implementations have different mechanisms for discovering repositories
- Most public registries only allow search (not exhaustive listing)
- If we decide that we still want it in the tool, consider leveraging [`crane`][crane_catalog] as it likely takes care of some edge cases 

  [crane_catalog]: https://github.com/google/go-containerregistry/blob/main/cmd/crane/doc/crane_catalog.md

### Layer content scanning
- point-in-time-scanning (vulns, EOL, bad licenses, etc.)
- application or OS config issues
- SBOM, files and dirs checksums
