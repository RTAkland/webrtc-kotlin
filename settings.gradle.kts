pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev/")
    }
}

rootProject.name = "webrtc-kotlin"

include(":datachannel")
include(":libdatachannel")
include(":mbedtls")
