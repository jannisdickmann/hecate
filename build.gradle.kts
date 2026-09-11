// Every plugin is declared here, unapplied, so that Gradle loads each one into a
// single classloader shared by the subprojects. Declaring them per-module instead
// gives each module its own copy of the Kotlin Gradle plugin, which KGP warns about
// on every build and does not support; the crash it guards against is an
// IsolatedKotlinClasspathClassCastException between identically-named classes.
//
// Kotlin cannot be hoisted here alone. AGP 9 has Kotlin support built in, so KGP on
// a classloader with no AGP on it fails at once with `Could not create an instance
// of type ...KotlinAndroidTarget > com/android/build/gradle/api/BaseVariant`. The
// two move together or not at all.
//
// The cost, accepted deliberately: AGP is now on the root classpath for every
// invocation, so even `-Phecate.androidModules=false ./gradlew :core:test` resolves
// it, and :core no longer builds on a machine that cannot reach Google's Maven.
// Versions stay in the catalog; the modules repeat the aliases, which is free once
// the same coordinates are declared here.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}

// A typed Delete rather than `doLast { delete(...) }`: the latter captures a
// reference to the build script itself, which the configuration cache cannot
// serialise.
tasks.register<Delete>("cleanAll") {
    group = "build"
    description = "Deletes the root build directory."
    delete(layout.buildDirectory)
}
