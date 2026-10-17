rootProject.name = "trusted-time-kmp"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":trusted-time")
include(":sample-app")
include(":sample-jvm")
include(":sample-web")
