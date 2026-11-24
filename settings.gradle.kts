pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Solo para NewPipeExtractor (resolución de tráilers en el aparato).
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe"); includeGroup("com.github.TeamNewPipe.NewPipeExtractor"); includeGroup("com.github.teamnewpipe") } }
    }
}
rootProject.name = "firetv-launcher"
include(":app")
