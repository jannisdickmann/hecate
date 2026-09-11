import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// §6.3 release signing: coordinates live in keystore.properties at the repository
// root and are never committed. Read once here — `java.util.Properties` cannot be
// spelled out inside the `signingConfigs` lambda, whose receiver shadows `java`.
val keystoreProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "de.hecate.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.hecate.phone"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Room schemas are exported so a future migration has something to diff.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    signingConfigs {
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

    buildFeatures { compose = true }

    lint {
        // targetSdk deliberately trails compileSdk. compileSdk 37 is what Compose
        // 2026.09 requires and changes no runtime behaviour on its own; raising
        // targetSdk opts the app into new platform behaviour, and there is no
        // device here to check that against (OPEN-POINTS §1.2, §1.3). Raise it in
        // a session that can wear the result.
        disable += "OldTargetApi"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.health.connect.client)
    implementation(libs.play.services.wearable)

    // §3.2 stands — no instrumented tests, and no Robolectric. These are plain
    // JVM tests of the backup archive's ZIP/JSON layer, which touches neither
    // Android nor Room and is the one part of this module where a silent bug
    // costs the user their history.
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
