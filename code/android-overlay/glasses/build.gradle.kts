plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    sourceSets.getByName("main").java.srcDir("../shared/src/main/kotlin")
    namespace = "ai.opstate.rokid.glasses"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        testApplicationId = "ai.opstate.rokid.glasses.librarytest"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    signingConfigs.getByName("debug") {
        providers.environmentVariable("OPSTATE_DEBUG_KEYSTORE").orNull?.let { storeFile = file(it) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":opstate-core"))
    api(project(":opstate-protocol"))
    implementation("com.rokid.cxr:cxr-service-bridge:1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.compose.runtime:runtime:1.7.6")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
