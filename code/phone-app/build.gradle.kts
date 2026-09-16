plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}
android {
    namespace="ai.opstate.rokid.phone.app"
    compileSdk=35
    defaultConfig {
        applicationId="ai.opstate.rokid.phone"
        minSdk=28
        targetSdk=35
        versionCode=5
        versionName="0.5.0-dev"
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig=true; compose=true }
    signingConfigs.getByName("debug") {
        providers.environmentVariable("OPSTATE_DEBUG_KEYSTORE").orNull?.let { storeFile = file(it) }
    }
    sourceSets.getByName("androidTest") {
        java.srcDir(rootProject.file("code/testing/runtime/kotlin"))
        assets.srcDir(rootProject.file("code/testing/runtime/assets"))
    }
    compileOptions {
        sourceCompatibility=JavaVersion.VERSION_17
        targetCompatibility=JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":opstate-phone"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.7.6")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
