import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.smbj)
    implementation(libs.smbj.rpc)
    constraints {
        // smbj-rpc 0.12.13 pulls commons-io 2.7 and commons-lang3 3.4, both with published flaws
        // (CVE-2021-29425, CVE-2025-48924): newer releases of the same major version take their place
        implementation(libs.commons.io)
        implementation(libs.commons.lang3)
    }
    testImplementation(libs.junit)
    testImplementation(libs.slf4j.nop)
}
