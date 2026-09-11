pluginManagement {
    repositories {
        // mavenCentral() first: everything :core needs lives there, so a machine
        // without access to Google's Maven can still build and test the algorithms.
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "hecate"

// :core is pure JVM Kotlin and always participates.
include(":core")

// The Android modules need an Android SDK. On a machine without one (CI that only
// exercises :core, for instance) they are skipped rather than failing configuration.
// Pass -Phecate.androidModules=true to force them in, or =false to force them out.
val forced: String? = providers.gradleProperty("hecate.androidModules").orNull
val sdkFromLocalProperties: String? = file("local.properties")
    .takeIf { it.exists() }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir") }
val sdkDir: String? = sdkFromLocalProperties
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

val includeAndroid = when (forced) {
    "true" -> true
    "false" -> false
    else -> sdkDir != null
}

if (includeAndroid) {
    include(":wear")
    include(":phone")
} else {
    logger.lifecycle(
        "hecate: no Android SDK found (sdk.dir / ANDROID_HOME / ANDROID_SDK_ROOT unset) — " +
            "configuring :core only. See README.md."
    )
}
