plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.graalvm.buildtools.native") version "0.10.4"
}

dependencies {
    implementation(project(":harless-core"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.harless.app.MainKt")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("harless")
            mainClass.set("dev.harless.app.MainKt")
            buildArgs.add("--no-fallback")
            buildArgs.add("-O2")
            buildArgs.add("--initialize-at-build-time=kotlin,kotlinx")
        }
    }
    // The toolchain detection is disabled so the JVM build works on any JDK;
    // the native image is built in CI on a real GraalVM distribution.
    toolchainDetection.set(false)
}
