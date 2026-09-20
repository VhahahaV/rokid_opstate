import java.security.MessageDigest

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    androidResources { noCompress += "onnx" }
    sourceSets.getByName("main").java.srcDir("../shared/src/main/kotlin")
    namespace = "ai.opstate.rokid.phone"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        testApplicationId = "ai.opstate.rokid.phone.librarytest"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val directSources=listOf("DirectModelClient.kt","DirectModelConfig.kt","ResponseParser.kt","WireDtos.kt")
            .flatMap {file("src/main/kotlin/ai/opstate/rokid/phone/runtime/$it").readBytes().toList()}.toByteArray()
        val directHash=MessageDigest.getInstance("SHA-256").digest(directSources).joinToString("") {"%02x".format(it.toInt() and 255)}
        buildConfigField("String","DIRECT_MODEL_CODE_SHA256","\"$directHash\"")
    }
    buildFeatures {buildConfig=true}
    signingConfigs.getByName("debug") {
        providers.environmentVariable("OPSTATE_DEBUG_KEYSTORE").orNull?.let { storeFile = file(it) }
    }
    sourceSets.getByName("androidTest") {
        java.srcDir(rootProject.file("code/testing/runtime/kotlin"))
        assets.srcDir(rootProject.file("code/testing/runtime/assets"))
        assets.srcDir(rootProject.file("code/phone-app/src/debug/assets"))
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
    // CVTT v1 is supplied as frozen ONNX graphs and runs here, on the frames a service session uploads.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    api(project(":opstate-core"))
    api(project(":opstate-protocol"))
    implementation("com.rokid.cxr:client-m:1.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
ksp { arg("room.schemaLocation", "$projectDir/room-schemas") }
