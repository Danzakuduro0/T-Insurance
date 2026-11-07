rootProject.name = "TInsurance"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Общие модели API (DTO, статусы, пути) из backend/core.
includeBuild("../backend") {
    dependencySubstitution {
        substitute(module("ru.tinsurance:core")).using(project(":core"))
    }
}

include(":androidApp")
include(":shared")
include(":webApp")