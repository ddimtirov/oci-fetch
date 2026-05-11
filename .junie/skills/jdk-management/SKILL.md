---
name: jdk-management
description: Explains the Gradle Toolchains configuration for Java and other SDKs in this project. Use before running Gradle, when troubleshooting build issues or setting up the environment.
---

# SDK/JDK Management

This project uses Gradle Toolchains to decide on the SDKs (including Java) used for the build.

When running Gradle, if it does not discover Java, use the newest from `~/jdks/*`

## SDK/JDK Versions

- **Gradle Wrapper Invocation:** uses Java 26 from the Jetbrains-managed location
- **Gradle Build Tasks:** use Java 25 configured as a toolchain 

## SDK/JDK Locations

- **JetBrains-managed JDKs:** Located under `~/.jdks/temurin-<ver>/`.
- **Gradle-managed JDKs:** Located under `${GRADLE_USER_HOME-~}/jdks/eclipse_adoptium-<version>-amd64-windows.<suffix>/>`.
- **Gradle-managed Node.js runtimes:** Located under `${GRADLE_USER_HOME-~}/node/node-v<semver>-win-64/`.
- **Gradle-managed Yarn tool:** Located under `${GRADLE_USER_HOME-~}/yarn/yarn-v<semver>/`.

