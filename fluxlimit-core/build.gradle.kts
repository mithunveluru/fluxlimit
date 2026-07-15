plugins {
    `java-library`
}

description = "FluxLimit core: rate limiting API, algorithms, and in-memory store. Zero runtime dependencies."

dependencies {
    // optional, loaded only when a MeterRegistry is supplied
    compileOnly(libs.micrometer.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}
