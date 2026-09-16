plugins { kotlin("jvm") }

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets {
        main { kotlin.setSrcDirs(listOf("src")) }
        test { kotlin.setSrcDirs(listOf("tests")) }
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val coreTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run the existing dependency-free core regression suite."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.opstate.core.tests.CoreTestsKt")
}
tasks.test { dependsOn(coreTest) }

tasks.register<JavaExec>("demo") {
    group = "application"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ai.opstate.core.simulation.MainKt")
}
tasks.register<JavaExec>("meetingDemo") {
    group = "application"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ai.opstate.core.simulation.MeetingSequenceKt")
}
