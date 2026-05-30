plugins {
    id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0")
}
rootProject.name = "oci-fetch"
include(":oci-fetch-lib")
include(":oci-fetch-cli")
include(":integration-tests")
include(":system-test")
