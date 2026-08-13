plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
}

group = providers.gradleProperty("projectGroup").get()
