pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev/")
    }
}

rootProject.name = "webrtc-kotlin"

include(":core")
project(":core").name = "webrtc-kotlin-core"
include(":libdatachannel")
include(":mbedtls")
