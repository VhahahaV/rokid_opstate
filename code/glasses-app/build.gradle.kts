plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace="ai.opstate.rokid.glasses.app"
    compileSdk=35
    defaultConfig {
        applicationId="ai.opstate.rokid.glasses"
        minSdk=28
        targetSdk=35
        versionCode=5
        versionName="0.5.0-dev"
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig=true }
    signingConfigs.getByName("debug") {
        providers.environmentVariable("OPSTATE_DEBUG_KEYSTORE").orNull?.let { storeFile = file(it) }
    }
    compileOptions {
        sourceCompatibility=JavaVersion.VERSION_17
        targetCompatibility=JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":opstate-glasses"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
