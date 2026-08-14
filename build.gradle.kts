plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
}

group = providers.gradleProperty("projectGroup").get()

subprojects {
    group = rootProject.group
    version = "0.1.0-SNAPSHOT"
}
