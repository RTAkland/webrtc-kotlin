import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

kotlin {
    linuxX64()
    linuxArm64()
    mingwX64()
    if (System.getProperty("os.name").startsWith("Mac")) macosArm64()

    explicitApi()
    withSourcesJar()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].cinterops {
            create("mbedtls") {
                definitionFile.set(file("src/cinterop/mbedtls.def"))
                extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
            }
        }
    }
}