kotlin {
    linuxX64()
    linuxArm64()
    mingwX64()
    if (System.getProperty("os.name").startsWith("Mac")) macosArm64()

    explicitApi()
    withSourcesJar()

    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines)
        }

        nativeMain.dependencies {
            implementation(project(":mbedtls"))
            implementation(project(":libdatachannel"))
        }

        commonTest.dependencies {
            implementation("cn.rtast.crypto-kotlin:crypto:0.1.1")
        }
    }

    compilerOptions.freeCompilerArgs.addAll("-Xexpect-actual-classes")
}
