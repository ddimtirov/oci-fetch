plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(plugin(libs.plugins.kotlin.multiplatform))
    implementation(plugin(libs.plugins.kotlin.serialization))
}

fun org.gradle.kotlin.dsl.DependencyHandlerScope.plugin(
    plugin: org.gradle.api.provider.Provider<org.gradle.plugin.use.PluginDependency>
): org.gradle.api.provider.Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
