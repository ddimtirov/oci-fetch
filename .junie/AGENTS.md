# oci-fetch Agent Instructions

This document outlines the general guidelines specific to the `oci-fetch` project. 
These instructions should be followed beyond specific tasks.

## 1. API Design and Usability

- **Documentation:** Create KDoc documentation only when the intent is not obvious from the code.
- Use `xxx?.let { ... }` in preference to `if (xxx != null) { ... }`, unless you have an `else` clause.

## 2. Error Handling

- **No Null for Exceptions:** Never replace an exception with `null` unless you can meaningfully recover through a fallback action. Replacing exceptions with `null` only propagates the error further away from the root cause.
- **Assertions:** Use assertions, `check()`, `require()` and `requireNotNull()` to check pre/post-conditions and invariants.
- API functions should not print – return a result or throw exception. Error callbacks can be used, but only when they really add value.
- Application should print payload to stdout and diagnostics to stderr.

## 3. Command Line Application

- Use the command line application as a reference implementation.

## 4. Platform and Testing Environment

- **Supported Platforms:** The library supports JVM, JavaScript (Node.js), WASM-JS (Node.js), Native Windows (mingwX64), and Native Linux (linuxX64). The application targets Native Linux and Windows.
- **Native Implementation:** Native platforms use the Curl Ktor HTTP engine, as CIO does not have support for TLS 1.3.
- **Browser Limitations:** JavaScript and WASM-JS browser tests fail due to CORS or networking issues when accessing external registries. These should be considered when adding new integration tests.
