plugins {
    id("com.android.application")
}

// The repository root: Android/ sits next to Engine/ and scripts/.
val repoRoot: File = rootDir.parentFile
val soundAssets = layout.buildDirectory.dir("generated/keySoundAssets")
val prepareKeySounds = tasks.register<Sync>("prepareKeySounds") {
    from(repoRoot.resolve("assets/key-sounds")) { include("**/*.wav"); into("key-sounds") }
    into(soundAssets)
}

android {
    namespace = "com.krysta1ll.meowpuut.android"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.krysta1ll.meowpuut.android"
        minSdk = 26
        targetSdk = 37
        val buildNumber = 12
        versionCode = buildNumber
        versionName = "稜鏡 · AND-" + buildNumber.toString().padStart(3, '0')
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DMEOWPUUT_ROOT=${repoRoot.absolutePath}")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The lexicon is mapped straight out of the APK, so it must stay uncompressed.
    androidResources {
        noCompress += listOf("bin", "wav")
    }

    sourceSets.getByName("main").assets.srcDir(soundAssets.get().asFile)

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs for org.json; the tests need the real one.
    testImplementation("org.json:json:20260814")
}

// Builds the Rust engine for each ABI and stages the lexicon assets.
val prepareEngine = tasks.register<Exec>("prepareEngine") {
    group = "build"
    description = "Runs scripts/build-android.sh --prepare"
    workingDir = repoRoot
    commandLine("bash", "scripts/build-android.sh", "--prepare")
}

tasks.named("preBuild") {
    dependsOn(prepareEngine, prepareKeySounds)
}
