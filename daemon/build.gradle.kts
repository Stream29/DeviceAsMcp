plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    linuxX64 {
        binaries.executable { entryPoint = "io.github.stream29.mcp.device.daemon.main" }
        compilations.named("main") {
            cinterops.create("daemonPosix") {
                defFile(project.file("src/nativeInterop/cinterop/daemon_posix.def"))
            }
        }
    }
    linuxArm64 {
        binaries.executable { entryPoint = "io.github.stream29.mcp.device.daemon.main" }
        compilations.named("main") {
            cinterops.create("daemonPosix") {
                defFile(project.file("src/nativeInterop/cinterop/daemon_posix.def"))
            }
        }
    }
    macosArm64 {
        binaries.executable { entryPoint = "io.github.stream29.mcp.device.daemon.main" }
        compilations.named("main") {
            cinterops.create("daemonPosix") {
                defFile(project.file("src/nativeInterop/cinterop/daemon_posix.def"))
            }
        }
    }
    mingwX64 {
        binaries.executable { entryPoint = "io.github.stream29.mcp.device.daemon.main" }
        compilations.named("main") {
            cinterops.create("daemonConPty") {
                defFile(project.file("src/nativeInterop/cinterop/daemon_conpty.def"))
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain")
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.okio)
        }
        val nativeMain = create("nativeMain") {
            dependsOn(commonMain)
        }
        val linuxMain = create("linuxMain") {
            dependsOn(nativeMain)
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }
        val unixMain = create("unixMain") {
            dependsOn(nativeMain)
        }
        linuxX64Main.get().apply {
            dependsOn(linuxMain)
            dependsOn(unixMain)
        }
        linuxArm64Main.get().apply {
            dependsOn(linuxMain)
            dependsOn(unixMain)
        }
        macosArm64Main.get().apply {
            dependsOn(unixMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        mingwX64Main.get().apply {
            dependsOn(nativeMain)
            dependencies {
                implementation(libs.ktor.client.winhttp)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
