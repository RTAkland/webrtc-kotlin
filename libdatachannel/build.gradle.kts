import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

kotlin {
    linuxX64()
    linuxArm64()
    mingwX64()

    explicitApi()
    withSourcesJar()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].cinterops {
            if (this@configureEach.name == "mingwX64") create("throw_bad_array_new_length") {
                definitionFile.set(file("src/cinterop/throw_bad_array_new_length.def"))
                extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
            }

            create("libdatachannel") {
                definitionFile.set(file("src/cinterop/libdatachannel.def"))
                extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
                includeDirs(file("src/cinterop/include/libdatachannel/include"))
            }
        }
    }
}