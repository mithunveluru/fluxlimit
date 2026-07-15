plugins {
    `java-library`
}

description = "FluxLimit Redis store backed by Lettuce."

dependencies {
    api(project(":fluxlimit-core"))
    api(libs.lettuce.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    // surfaces testcontainers logs in test output
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
