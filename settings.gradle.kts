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
    }
}

rootProject.name = "SyntheticEngine"

// Pure-Kotlin engine core (sensor fusion, engine model, synthesizer). Built as an
// included build so it can also be tested/run on its own: ./gradlew -p engine test
includeBuild("engine")
include(":app")
