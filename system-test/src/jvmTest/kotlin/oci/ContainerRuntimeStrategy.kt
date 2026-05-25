package oci

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal sealed class ContainerRuntimeStrategy(val binary: String) {
    abstract fun checkGripes(): Collection<String>
    abstract fun cmdCreateAndPushIndex(indexTag: OciRef, firstImage: OciRef, secondImage: OciRef)
    abstract fun cmdBuildAndPushAttested(attestedTag: OciRef, buildDir: Path): OciRef
    abstract fun cmdDigestFor(tag: OciRef): String
    abstract fun cmdPushAs(image: OciRef, alias: OciRef): OciRef
    protected fun normalizeDigest(raw: String): String {
        val cleaned = raw.trim().trim('\'', '"')
        val digest = cleaned.substringAfterLast('@')
        check(digest.startsWith("sha256:")) { "Unexpected digest format: '$raw'" }
        return digest
    }

    fun cmdPull(vararg images: String) = images.map {
        cmd("pull", it)
        OciRef.parse(it)
    }


    fun cmd(vararg args: String): String {
        val result = runCommand(binary, *args)
        check(result.exitCode == 0) {
            "Container command failed: $binary ${args.joinToString(" ")}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        }
        return result.stdout
    }

    protected data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

    protected companion object {
        fun runCommand(vararg cmd: String): CmdResult {
            val process = try {
                ProcessBuilder(*cmd).redirectErrorStream(false).start()
            } catch (e: Exception) {
                return CmdResult(-1, "", "Failed to start '${cmd.joinToString(" ")}': ${e.message}")
            }

            val pool = Executors.newFixedThreadPool(2)
            val stdoutFuture = pool.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
            val stderrFuture = pool.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }

            return try {
                if (process.waitFor(10, TimeUnit.MINUTES)) {
                    CmdResult(process.exitValue(), stdoutFuture.get(5, TimeUnit.SECONDS), stderrFuture.get(5, TimeUnit.SECONDS))
                } else {
                    process.destroyForcibly()
                    CmdResult(-1, stdoutFuture.get(5, TimeUnit.SECONDS), "Timed out: ${cmd.joinToString(" ")}\n${stderrFuture.get(5, TimeUnit.SECONDS)}")
                }
            } catch (e: Exception) {
                process.destroyForcibly()
                CmdResult(-1, "", "Failed while running '${cmd.joinToString(" ")}': ${e.message}")
            } finally {
                pool.shutdownNow()
            }
        }
    }
}

internal class DockerRuntimeStrategy private constructor() : ContainerRuntimeStrategy("docker") {
    override fun checkGripes(): Collection<String> {
        runCommand("docker", "buildx", "version").let {
            if (it.exitCode != 0) return listOf("docker buildx is unavailable")
        }

        return runCommand("docker", "buildx", "build", "--help").let {
            val gripes = mutableListOf<String>()
            if (!it.stdout.contains("--sbom")) gripes.add("docker buildx SBOM attestations are unavailable")
            if (!it.stdout.contains("--provenance"))  gripes.add("docker buildx Provenance attestations are unavailable")
            gripes
        }
    }

    override fun cmdDigestFor(tag: OciRef): String =
        normalizeDigest(cmd("inspect", "--format", "{{index .RepoDigests 0}}", "$tag"))

    override fun cmdPushAs(image: OciRef, alias: OciRef): OciRef {
        cmd("tag", "$image", "$alias")
        cmd("push", "$alias", "--insecure-skip-verify")
        return alias
    }

    override fun cmdCreateAndPushIndex(indexTag: OciRef, firstImage: OciRef, secondImage: OciRef) {
        cmd("buildx", "imagetools", "create", "--tag", "--insecure-skip-verify", "$indexTag", "$firstImage", "$secondImage")
    }

    override fun cmdBuildAndPushAttested(attestedTag: OciRef, buildDir: Path): OciRef {
        cmd(
            "buildx",
            "build",
            "--platform", "linux/amd64",
            "--provenance=true",
            "--sbom=true",
            "--push",
            "--insecure-skip-verify",
            "--tag", "$attestedTag",
            "$buildDir"
        )
        val digest = cmdDigestFor(attestedTag)
        return attestedTag.withDigest(digest)
    }

    companion object {
        fun fromEnvironment(): DockerRuntimeStrategy? {
            val dockerVersion = runCommand("docker", "version")
            if (dockerVersion.exitCode == 0) {
                return DockerRuntimeStrategy()
            }

            val dockerInstalled = runCommand("docker", "--version").exitCode == 0
            check(!dockerInstalled) {
                val details = dockerVersion.stderr.ifBlank { dockerVersion.stdout }.ifBlank { "unknown runtime error" }
                "Docker is installed but not working: $details"
            }
            return null
        }
    }
}

internal class PodmanRuntimeStrategy private constructor() : ContainerRuntimeStrategy("podman") {
    override fun checkGripes(): Collection<String> {
        runCommand("podman", "manifest", "--help").let {
            if (it.exitCode != 0) return listOf("podman manifest commands are unavailable")
        }

        return runCommand("podman", "build", "--help").let {
            if (!it.stdout.contains("--sbom")) listOf("podman SBOM attestations are unavailable")
            else emptyList()
        }
    }

    override fun cmdDigestFor(tag: OciRef): String =
        normalizeDigest(cmd("inspect", "$tag", "--format", "{{.Digest}}"))

    override fun cmdPushAs(image: OciRef, alias: OciRef): OciRef {
        cmd("tag", "$image", "$alias")
        cmd("push", "--tls-verify=false", "$alias")
        return alias
    }

    override fun cmdCreateAndPushIndex(indexTag: OciRef, firstImage: OciRef, secondImage: OciRef) {
        cmd("manifest", "rm", "--ignore", "$indexTag")
        cmd("manifest", "create", "$indexTag")
        cmd("manifest", "add", "$indexTag", "$firstImage")
        cmd("manifest", "add", "$indexTag", "$secondImage")
        cmd("manifest", "push", "--tls-verify=false", "--all", "$indexTag", "docker://$indexTag")
    }

    override fun cmdBuildAndPushAttested(attestedTag: OciRef, buildDir: Path): OciRef {
        cmd("build", "--sbom", "syft", "--sbom-image-output", "/sbom.spdx.json", "--tag", "$attestedTag", "$buildDir")
        cmd("push", "--tls-verify=false", "$attestedTag")
        val digest = cmdDigestFor(attestedTag)
        return attestedTag.withDigest(digest)
    }

    companion object {
        fun fromEnvironment(): PodmanRuntimeStrategy? {
            val podmanVersion = runCommand("podman", "version")
            if (podmanVersion.exitCode == 0) {
                return PodmanRuntimeStrategy()
            }

            val podmanInstalled = runCommand("podman", "--version").exitCode == 0
            check(!podmanInstalled) {
                val details = podmanVersion.stderr.ifBlank { podmanVersion.stdout }.ifBlank { "unknown runtime error" }
                "Podman is installed but not working: $details"
            }
            return null
        }
    }
}
