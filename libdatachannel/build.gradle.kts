import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * Generates libdatachannel headers and static libraries when they are missing or
 * when [version] differs from the installed VERSION marker.
 *
 * The generated files are intentionally not tracked by git. This task runs before
 * the cinterop tasks, so a clean checkout bootstraps itself, and changing
 * `-PlibdatachannelVersion=...` regenerates everything.
 */
abstract class PrepareNativeLibs : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val targets: Property<String>

    @get:Input
    @get:Optional
    abstract val source: Property<String>

    @get:Input
    @get:Optional
    abstract val mbedtlsSource: Property<String>

    @get:Input
    @get:Optional
    abstract val jobs: Property<String>

    @get:Internal
    abstract val projectDirPath: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun prepare() {
        val dir = projectDirPath.get().asFile
        val targetList =
            targets
                .get()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val versionFile = dir.resolve("src/cinterop/include/libdatachannel/VERSION")
        val headerFile = dir.resolve("src/cinterop/include/libdatachannel/include/rtc/rtc.h")
        val installed = versionFile.takeIf { it.isFile }?.readText()?.trim()
        val missingLibs =
            targetList.any { target ->
                listOf("libdatachannel.a", "libjuice.a", "libusrsctp.a").any { lib ->
                    !dir.resolve("src/cinterop/libs/$target/$lib").isFile
                }
            }

        if (installed == version.get() && headerFile.isFile && !missingLibs) return

        val args =
            mutableListOf(
                "bash",
                dir.resolve("scripts/update-native-libs.sh").absolutePath,
                "--version",
                version.get(),
                "--targets",
                targetList.joinToString(","),
            )
        source.orNull?.let { args += listOf("--source", it) }
        mbedtlsSource.orNull?.let { args += listOf("--mbedtls-source", it) }
        jobs.orNull?.let { args += listOf("--jobs", it) }

        execOperations.exec {
            workingDir = dir
            commandLine(args)
        }
    }
}

val isMacHost = providers.systemProperty("os.name").map { it.startsWith("Mac") }.get()
val libdatachannelVersion = providers.gradleProperty("libdatachannelVersion").orElse("0.24.8")
val explicitTargets = providers.gradleProperty("libdatachannelTargets")
val autoTargets =
    explicitTargets.orElse(if (isMacHost) "macosArm64" else "linuxX64,linuxArm64,mingwX64")
val allTargets = explicitTargets.orElse("linuxX64,linuxArm64,mingwX64,macosArm64")
val libdatachannelSource = providers.gradleProperty("libdatachannelSource")
val mbedtlsSourceProp = providers.gradleProperty("mbedtlsSource")
val nativeLibsJobs = providers.gradleProperty("libdatachannelJobs")
val autoUpdate = providers.gradleProperty("libdatachannelAutoUpdate").orElse("true").map(String::toBoolean)

val prepareNativeLibs =
    tasks.register<PrepareNativeLibs>("prepareNativeLibs") {
        group = "libdatachannel"
        description = "Generate libdatachannel headers and static libraries if missing or out of date."
        version.set(libdatachannelVersion)
        targets.set(autoTargets)
        projectDirPath.set(layout.projectDirectory)
        source.set(libdatachannelSource)
        mbedtlsSource.set(mbedtlsSourceProp)
        jobs.set(nativeLibsJobs)
    }

if (autoUpdate.get()) {
    tasks.configureEach {
        if (name.startsWith("cinteropLibdatachannel")) {
            dependsOn(prepareNativeLibs)
        }
    }
}

// The static libraries are consumed by the native linker through -libraryPath,
// which Gradle does not track automatically. Declare them (and the headers) as
// link inputs so updating the version/artifacts forces a relink.
tasks.configureEach {
    if (name.startsWith("link")) {
        inputs
            .files(
                fileTree(projectDir.resolve("src/cinterop/libs")) { include("*/*.a") },
                fileTree(projectDir.resolve("src/cinterop/include/libdatachannel/include")) {
                    include("**/*.h", "**/*.hpp")
                },
            ).withPropertyName("libdatachannelNativeArtifacts")
    }
}

tasks.register<Exec>("updateNativeLibs") {
    group = "libdatachannel"
    description = "Download libdatachannel sources and rebuild static libraries and headers."

    val args =
        mutableListOf(
            file("scripts/update-native-libs.sh").absolutePath,
            "--version",
            libdatachannelVersion.get(),
            "--targets",
            allTargets.get(),
        )
    libdatachannelSource.orNull?.let { args += listOf("--source", it) }
    mbedtlsSourceProp.orNull?.let { args += listOf("--mbedtls-source", it) }
    nativeLibsJobs.orNull?.let { args += listOf("--jobs", it) }

    workingDir = projectDir
    commandLine("bash", *args.toTypedArray())
}

kotlin {
    linuxX64()
    linuxArm64()
    mingwX64()
    if (isMacHost) macosArm64()

    explicitApi()
    withSourcesJar()

    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].cinterops {
            if (this@configureEach.name == "mingwX64") {
                create("throw_bad_array_new_length") {
                    definitionFile.set(file("src/cinterop/throw_bad_array_new_length.def"))
                    extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
                }
            }

            create("libdatachannel") {
                definitionFile.set(file("src/cinterop/libdatachannel.def"))
                extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
                includeDirs(file("src/cinterop/include/libdatachannel/include"))
            }
        }
    }
}
