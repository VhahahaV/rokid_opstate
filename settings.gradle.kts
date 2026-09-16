pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.1.21"
        kotlin("android") version "2.1.21"
        kotlin("plugin.serialization") version "2.1.21"
        kotlin("plugin.compose") version "2.1.21"
        id("com.android.library") version "8.7.2"
        id("com.android.application") version "8.7.2"
        id("com.google.devtools.ksp") version "2.1.21-2.0.2"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.rokid.com/repository/maven-public/")
            content { includeGroup("com.rokid.cxr") }
        }
    }
}

rootProject.name = "opstate-rokid"
include(":opstate-core")
project(":opstate-core").projectDir = file("code/core")
include(":opstate-protocol")
project(":opstate-protocol").projectDir = file("code/protocol")

// Core tests and simulations must also work on computers without an Android SDK.
if (providers.gradleProperty("withAndroid").orNull == "true") {
    include(":opstate-phone", ":opstate-glasses", ":phone-app", ":glasses-app")
    project(":opstate-phone").projectDir = file("code/android-overlay/phone")
    project(":opstate-glasses").projectDir = file("code/android-overlay/glasses")
    project(":phone-app").projectDir = file("code/phone-app")
    project(":glasses-app").projectDir = file("code/glasses-app")
}
