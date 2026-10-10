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
        // Shared with the other module on purpose. The Wearable Data Layer is
        // package-scoped: a DataItem published by one applicationId is only
        // deliverable to the same applicationId on the other node, so two
        // different ids mean the phone never sees a session (§5.4).
        applicationId = "de.hecate"
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
        // device here to check that against (.agents/brain/issues/
        // target-sdk-trails-compile-sdk-deliberately.md, OP-2.7). Raise it in a
        // session that can wear the result.
        disable += "OldTargetApi"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and the resource table; without
            // this it starts and then fails on the first thing that reads either.
            isIncludeAndroidResources = true
            // JUnit 4, deliberately: Robolectric's runner is a JUnit 4 runner, and
            // running it under the platform means adding the vintage engine to get
            // back exactly what is already here. :core is on the platform; the two
            // modules differ because their test frameworks do.
            all {
                it.testLogging {
                    events("passed", "failed", "skipped")
                }
                // Robolectric reaches into the JDK's internals to fake Android's —
                // `FileDescriptorInterceptor` calls `jdk.internal.access.SharedSecrets`
                // to set a raw fd, which every JDK since 9 refuses by default and
                // JDK 25 refuses loudly. The failure is a bare IllegalAccessException
                // from inside the sandbox with no hint that it is a module problem,
                // so: opened here rather than rediscovered later.
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                )
            }
        }
    }

    // Room's exported schemas are what MigrationTestHelper replays. They are a
    // build output, not a test resource, so the test source set is pointed at
    // them rather than the JSON being copied into two places that then drift.
    sourceSets.getByName("test") {
        assets.srcDir("$projectDir/schemas")
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.health.connect.client)
    implementation(libs.play.services.wearable)

    // The archive format's own tests moved to :core with the format (A.15). What
    // is left here needs Android: Room and its migrations, WorkManager, the
    // ContentResolver the backup goes through. Robolectric runs all three on the
    // JVM, so `./gradlew :phone:testDebugUnitTest` needs no phone plugged in.
    //
    // §3.2 of the open points still stands for everything Robolectric cannot
    // reach — Compose UI, the Wearable Data Layer, real permissions.
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
