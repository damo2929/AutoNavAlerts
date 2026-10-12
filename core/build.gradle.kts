plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.jsoup)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Lets the optional real-file tests see PGW_IGO_FILE and the drive replay's files, and prints their summaries.
    environment("PGW_IGO_FILE", System.getenv("PGW_IGO_FILE") ?: "")
    environment("DRIVE_CSV", System.getenv("DRIVE_CSV") ?: "")
    environment("DRIVE_POIS", System.getenv("DRIVE_POIS") ?: "")
    testLogging { showStandardStreams = true }
}
