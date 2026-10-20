import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Version: CI passes its run number as HECATE_VERSION_CODE
// (.github/workflows/android.yml), which becomes the version code and the last
// part of the name; local builds are 1. The prefix is `hecate.version` in
// gradle.properties, shared with the other module so the pair always match.
val hecateVersionCode = providers.environmentVariable("HECATE_VERSION_CODE").orNull?.toIntOrNull() ?: 1
val hecateVersionName = "${providers.gradleProperty("hecate.version").get()}.$hecateVersionCode"

// §6.3 release signing: coordinates live in keystore.properties at the repository
// root and are never committed. CI writes one from repository secrets. Read once here — `java.util.Properties` cannot be
// spelled out inside the `signingConfigs` lambda, whose receiver shadows `java`.
val keystoreProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "de.hecate.wear"
    compileSdk = 37

    defaultConfig {
        // Shared with the other module on purpose. The Wearable Data Layer is
        // package-scoped: a DataItem published by one applicationId is only
        // deliverable to the same applicationId on the other node, so two
        // different ids mean the phone never sees a session (§5.4).
        applicationId = "de.hecate"
        // §4: one device pair, no back-compat burden.
        minSdk = 36
        targetSdk = 36
        versionCode = hecateVersionCode
        versionName = hecateVersionName
    }

    signingConfigs {
        // §6.3: never ship with the debug keystore — a signature mismatch means an
        // uninstall, and an uninstall means losing any session not yet uploaded.
        // Point these at your own release keystore via keystore.properties.
        create("release") {
            keystoreProperties?.let { p ->
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProperties != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For VERSION_NAME, written into every session header.
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    // The JDK that runs the compiler, pinned so the build does not silently
    // inherit whatever `default-java` happens to be after an `apt upgrade`
    // (README step 1). Independent of `jvmTarget` below, which is the bytecode
    // level Android consumes and stays at 17.
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    constraints {
        // Not a dependency of this module: play-services-wearable reaches
        // androidx.fragment 1.1.0 through play-services-basement, and every
        // basement release pins that same 1.1.0. Fragment that old predates
        // FragmentActivity calling super.onRequestPermissionsResult(), so lint
        // rejects registerForActivityResult outright — correctly in general,
        // though the only activity here is a plain ComponentActivity. Constrain
        // rather than depend: nothing in this app uses fragments.
        implementation(libs.androidx.fragment)
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.ongoing)

    implementation(libs.health.services.client)
    // Health Services returns ListenableFuture from every *Async call but declares
    // Guava runtime-scope only, so the type is missing at compile time. It is on the
    // runtime classpath transitively already — compileOnly adds nothing to the APK.
    compileOnly(libs.guava)
    implementation(libs.play.services.wearable)
}
