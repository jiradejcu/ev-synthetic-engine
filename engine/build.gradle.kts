import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin engine core: vehicle motion fusion, engine/transmission model and
// the sound synthesizer. No Android dependencies, so it can be unit tested (and
// can render demo WAV files) on any JVM.
plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "com.syntheticengine"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// ./gradlew -p engine run  -> renders demo WAV files of a simulated launch.
application {
    mainClass.set("com.syntheticengine.engine.DemoRenderKt")
}
