import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// §6.3 release signing: coordinates live in keystore.properties at the repository
// root and are never committed. Read once here — `java.util.Properties` cannot be
// spelled out inside the `signingConfigs` lambda, whose receiver shadows `java`.
val keystoreProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "de.hecate.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.hecate.wear"
        // §4: one device pair, no back-compat burden.
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    buildFeatures { compose = true }

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
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.tooling.preview)

    implementation(libs.health.services.client)
    // Health Services returns ListenableFuture from every *Async call but declares
    // Guava runtime-scope only, so the type is missing at compile time. It is on the
    // runtime classpath transitively already — compileOnly adds nothing to the APK.
    compileOnly(libs.guava)
    implementation(libs.play.services.wearable)
}
