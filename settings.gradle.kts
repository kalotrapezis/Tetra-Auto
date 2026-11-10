pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // GeckoView (MPL 2.0): the Browser's engine, Firefox's, so Firefox's extensions run in it
        maven("https://maven.mozilla.org/maven2/") { content { includeGroup("org.mozilla.geckoview") } }
    }
}
rootProject.name = "Tetra Auto"
include(":app")

