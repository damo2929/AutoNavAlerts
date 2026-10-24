import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// One signing key for every build, so a single SHA-1 is registered with Google (DESIGN.md §13.3).
// It lives outside this public repo, in the private repo damo2929/autonavalerts-signing cloned to
// ~/.config/autonavalerts (or AUTONAV_SIGNING = a signing.properties path). Without it, builds use
// the PC's own debug key.
val sharedSigning: Properties? = (System.getenv("AUTONAV_SIGNING")
    ?.let(::file) ?: file("${System.getProperty("user.home")}/.config/autonavalerts/signing.properties"))
    .takeIf { it.isFile }
    ?.let { f ->
        Properties().apply {
            f.inputStream().use { load(it) }
            // storeFile is relative to signing.properties, so the folder works wherever it's cloned.
            setProperty("storeFile", f.parentFile.resolve(getProperty("storeFile")).path)
        }
    }

android {
    namespace = "io.autonav.alerts"
    compileSdk = 37

    defaultConfig {
        applicationId = "uk.damiendye.autonav"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        // -PversionName=… stamps a build (e.g. 0.0.1-2026-10-24); the default is the development version.
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
        // The git commit the build came from, shown in Settings → About; "-dirty" when the tree had changes.
        val commit = providers.exec { commandLine("git", "rev-parse", "--short=9", "HEAD"); isIgnoreExitValue = true }
            .standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
        val dirty = providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=no"); isIgnoreExitValue = true }
            .standardOutput.asText.map { if (it.isBlank()) "" else "-dirty" }
        buildConfigField("String", "GIT_COMMIT", "\"${commit.get()}${dirty.get()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        sharedSigning?.let { p ->
            create("shared") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
        release {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Navigation SDK requires desugaring for java.time etc. on older API levels.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Hilt's generated Java compile tasks need a full JDK too (the machine may only have a JRE).
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(libs.google.navigation)
    implementation(libs.play.services.location)
    implementation(libs.play.services.auth)
    // The Navigation SDK bundles its own Maps classes; Places must not pull in play-services-maps.
    implementation(libs.google.places) {
        exclude(group = "com.google.android.gms", module = "play-services-maps")
    }

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json for JVM tests (Android's is a stub there).
    testImplementation("org.json:json:20240303")
}
