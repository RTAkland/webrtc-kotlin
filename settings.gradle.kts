pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev/")
    }
}

rootProject.name = "webrtc-kotlin"

// The directory is core/; the Gradle project path and Maven artifact id are
// webrtc-kotlin-core.
include(":core")
project(":core").name = "webrtc-kotlin-core"
include(":libdatachannel")
include(":mbedtls")
