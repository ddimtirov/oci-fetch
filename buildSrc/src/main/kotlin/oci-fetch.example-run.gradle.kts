import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.tasks.Jar

tasks.register<JavaExec>("runExample") {
    group = "application"
    description = "Runs the JVM example that fetches JSON from an HTTP URL."
    dependsOn("jvmJar")
    classpath = files(tasks.named<Jar>("jvmJar").get().archiveFile) + configurations["jvmRuntimeClasspath"]
    mainClass = "example.FetchJsonKt"
    project.findProperty("url")?.let { systemProperties["url"] = it as String? }
}