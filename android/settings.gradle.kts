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

rootProject.name = "SecureVox"
include(":app")
include(":ratingkit")
project(":ratingkit").projectDir = file("/Users/sushanthtiruvaipati/Documents/GitHub/RatingKit-Android/ratingkit")
