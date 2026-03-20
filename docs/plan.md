# oci-fetch Improvement Plan

## Overview
This document outlines the improvement plan for the `oci-fetch` Kotlin library, which aims to provide a robust API for interacting with Docker and OCI (Open Container Initiative) registries. The plan is organized by functional areas and includes rationales for each proposed change.

## 1. Project Setup and Infrastructure

### 1.1 Kotlin Multiplatform Configuration
**Rationale:** Kotlin Multiplatform will allow the library to be used across different platforms (JVM, JS, Native), increasing its versatility and adoption potential.

**Tasks:**
- Update build.gradle.kts to use Kotlin Multiplatform plugin
- Configure common, JVM, JS, and Native targets
- Set up shared code structure for cross-platform compatibility
- Implement platform-specific code where necessary

### 1.2 Dependency Management
**Rationale:** Proper dependency management ensures the library uses up-to-date, secure, and compatible dependencies.

**Tasks:**
- Integrate Ktor HTTP client for network operations
- Add JSON parsing libraries (kotlinx.serialization)
- Configure logging framework
- Set up test dependencies for each platform

## 2. Core Functionality

### 2.1 Authentication System
**Rationale:** Flexible authentication is crucial for interacting with various registries that implement different authentication schemes.

**Tasks:**
- Implement `ociSetCredentials` function with registry-specific credential storage
- Support anonymous authentication as required by Docker Hub and Red Hat Quay
- Handle WWW-Authenticate headers and challenge-response flows
- Implement token caching and refresh mechanisms

### 2.2 Manifest Handling
**Rationale:** OCI manifests are central to container image metadata and must be handled correctly for all registry types.

**Tasks:**
- Implement `ociFetchManifest` function with proper error handling
- Create classes for different manifest types (Image, Index), each class should hold a reference to the original JSON, as well as the mime type, and expose usable data through getters. This is how we account for differences between the different schemas.  
- Support all common Docker and OCI MIME types
- Implement platform-specific manifest retrieval

### 2.3 Blob Operations
**Rationale:** Efficient and reliable blob retrieval is essential for accessing container layers and configuration data.

**Tasks:**
- Implement `fetchBlob` function with streaming support
- Add resume capability for large blob downloads
- Implement efficient caching mechanisms
- Support content verification via SHA256 checksums

## 3. API Design and Usability

### 3.1 API Surface Refinement
**Rationale:** A clean, intuitive API improves developer experience and reduces integration errors.

**Tasks:**
- Finalize API design following Kotlin idioms
- Implement builder patterns where appropriate
- Add extension functions for common operations
- Create KDoc documentation only when intent is not obvious from the code

### 3.2 Error Handling
**Rationale:** Robust error handling with meaningful messages helps developers diagnose and fix issues quickly.

**Tasks:**
- Let exceptions bubble up
- Use assertions to check pre/post-conditions and invariants
- Implement detailed error messages with troubleshooting hints
- Add retry mechanisms for transient failures
- Provide logging hooks for debugging

### 3.3 Sample Applications
**Rationale:** Examples demonstrate proper usage and serve as reference implementations.

**Tasks:**
- Create simple example downloading JSON from HTTP URL
- Implement comprehensive examples for each registry type
- Add examples for common workflows (tag listing, manifest inspection)
- Document examples with explanatory comments

## 4. Testing and Validation

### 4.1 Unit Testing
**Rationale:** Comprehensive unit tests ensure individual components work as expected.

**Tasks:**
- Implement tests for all public API functions
- Add tests for error conditions and edge cases
- Create mocks for external dependencies
- Ensure high code coverage

### 4.2 Integration Testing
**Rationale:** Integration tests verify that components work together correctly and interact properly with real registries.

**Tasks:**
- Set up tests against specified registries (Red Hat, Docker Hub)
- Implement tag fetching tests
- Create manifest retrieval tests for various image types
- Test recursive manifest and config fetching

## Conclusion
This improvement plan provides a roadmap for developing the `oci-fetch` library into a robust, well-documented, and user-friendly tool for interacting with Docker and OCI registries. By following this plan, the library will meet all the specified requirements while maintaining high standards of code quality, performance, and usability.