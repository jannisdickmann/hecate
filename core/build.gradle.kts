plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// NFR-4: :core is pure Kotlin. The only dependency is kotlinx.serialization (NFR-8).
// Nothing here may reference android.*; the dependency list below is the enforcement.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
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

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    // US-10: offline replay harness.
    mainClass.set("de.hecate.core.replay.ReplayMain")
}

tasks.named<JavaExec>("run") {
    // Gradle would otherwise run this from `core/`, which is not where any of the
    // paths in the README point: `fixtures/`, `takeout/` and the bundle files all
    // live at the root of the repository, and so does the .gitignore entry that
    // keeps recorded sessions out of git. Relative arguments now mean what they
    // look like they mean.
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
