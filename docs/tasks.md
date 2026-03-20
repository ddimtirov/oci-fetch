* [x] Setup gradle to use Kotlin Multiplatform and Ktor HTTP client.
* [x] Add a small executable example, downloading JSON from an HTTP URL.

How to run the example:
- Default URL: `./gradlew runExample`
- Custom URL: `./gradlew runExample -Durl=https://api.github.com`  

* [x] Change to produce a native executable

Windows (mingwX64) native executable:
- Build release exe: `./gradlew.bat linkReleaseExecutableMingwX64`
- Run: `build\bin\mingwX64\releaseExecutable\oci-fetch.exe [URL]`
  - If no URL is provided, defaults to https://httpbin.org/json
- For debug build: `./gradlew.bat linkDebugExecutableMingwX64` then run `build\bin\mingwX64\debugExecutable\oci-fetch.exe [URL]`
