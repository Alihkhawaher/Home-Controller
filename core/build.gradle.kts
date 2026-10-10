plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // Sunrise and sunset for sun triggers, computed locally (Apache-2.0, no dependencies; java.time, so Android 8+).
    implementation("org.shredzone.commons:commons-suncalc:3.11")
    testImplementation("junit:junit:4.13.2")
}
