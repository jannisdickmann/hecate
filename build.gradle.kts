// Intentionally empty of plugin declarations.
//
// The usual `plugins { alias(...) apply false }` block would force Gradle to resolve
// the Android Gradle Plugin marker even when the Android modules are not part of the
// build (see settings.gradle.kts). Each module declares its own plugins via the
// version catalog instead, which keeps versions in exactly one place anyway.
tasks.register("cleanAll") {
    group = "build"
    description = "Deletes the root build directory."
    doLast { delete(layout.buildDirectory) }
}
