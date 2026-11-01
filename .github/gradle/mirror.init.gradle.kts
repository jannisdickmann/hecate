// CI only (android-app.yml installs it into the runner's Gradle user home).
//
// Maven Central rate-limits shared egress, and a 429 is a hard failure —
// Gradle does not fall through to the next repository. GitHub's runners share
// their addresses, and a pull request starts with an empty cache (only main
// writes it), so a cold build asks Central for hundreds of artifacts and the
// first PR run failed exactly so. Google's mirror of Central serves the same
// files without the limit. The same script makes a cloud-sandbox build work
// (.agents/brain/development.md, "Building in a cloud sandbox").
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"

fun RepositoryHandler.mirrorFirst() {
    // Google first: AGP's aapt2 is only there, and asking the mirror for it
    // first costs a round trip per artifact.
    val g = google {
        name = "googleFirst"
        content {
            includeGroupByRegex("com\\.android.*")
            includeGroupByRegex("com\\.google.*")
            includeGroupByRegex("androidx.*")
        }
    }
    remove(g); add(0, g)
    val r = maven { name = "gcsMirror"; url = uri(mirror) }
    remove(r); add(1, r)
}

settingsEvaluated {
    pluginManagement.repositories.mirrorFirst()
    dependencyResolutionManagement.repositories.mirrorFirst()
}

allprojects {
    // Robolectric downloads android-all at test time, from inside the test
    // JVM: the repositories above never reach it.
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", mirror)
    }
}
