plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
