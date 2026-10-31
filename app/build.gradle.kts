import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.thamichele.smbcommander"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.thamichele.smbcommander"
        minSdk = 26
        targetSdk = 37
        // MAJOR.MINOR.PATCH, raised by hand in the change that needs it:
        // - PATCH for fixes that add or change no function
        // - MINOR (PATCH back to 0) for each new function or change
        // - MAJOR only when the owner says the app has reached a new maturity
        // Each merge to main publishes vMAJOR.MINOR.PATCH: a version already released makes the release job fail
        // instead of overwriting it. Up to 1.0.46 the last number was the CI build number.
        val appVersionMajor = 1
        val appVersionMinor = 1
        val appVersionPatch = 1
        versionCode = appVersionMajor * 1_000_000 + appVersionMinor * 1_000 + appVersionPatch
        versionName = "$appVersionMajor.$appVersionMinor.$appVersionPatch"
    }

    signingConfigs {
        getByName("debug") {
            // fixed debug key (committed on purpose, as in WebRadioDroid) so CI builds install over each other
            storeFile = file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // release key: never committed - GitHub Actions decodes it from the repository secrets (see .github/workflows/build-apk.yml)
        val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE_FILE")
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = "smbcommander"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // the debug key is public (committed above): its builds get their own identity, so an APK signed with it
            // can never install as an update of the real app and read its data
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

configurations.all {
    // smbj-rpc brings full Guava, which also provides the classes of AndroidX's "listenablefuture" stub
    resolutionStrategy.capabilitiesResolution.withCapability("com.google.guava:listenablefuture") {
        select("com.google.guava:guava:0")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.slf4j.nop)
}
