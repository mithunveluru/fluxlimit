plugins {
    `java-library`
}

description = "FluxLimit Spring Boot starter: auto-configuration and @RateLimit."

dependencies {
    api(project(":fluxlimit-core"))
    implementation(libs.spring.boot.autoconfigure)
    // core's micrometer adapter is what the starter auto-binds
    implementation(libs.micrometer.core)

    // provided by the host application's web starter
    compileOnly(libs.spring.webmvc)
    compileOnly(libs.jakarta.servlet.api)
    // activates only when the app adds fluxlimit-redis
    compileOnly(project(":fluxlimit-redis"))

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
