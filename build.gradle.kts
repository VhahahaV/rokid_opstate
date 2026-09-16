plugins {
    base
    id("com.android.library") apply false
    id("com.android.application") apply false
    id("com.google.devtools.ksp") apply false
    kotlin("jvm") apply false
    kotlin("android") apply false
    kotlin("plugin.serialization") apply false
    kotlin("plugin.compose") apply false
}

tasks.named("check") { dependsOn(":opstate-core:check", ":opstate-protocol:check") }
tasks.named("build") { dependsOn(":opstate-core:build", ":opstate-protocol:build") }

if (providers.gradleProperty("withAndroid").orNull == "true") {
    tasks.named("check") {
        dependsOn(":opstate-phone:check", ":opstate-glasses:check", ":phone-app:check", ":glasses-app:check")
    }
    tasks.named("build") {
        dependsOn(":opstate-phone:build", ":opstate-glasses:build", ":phone-app:build", ":glasses-app:build")
    }
}
