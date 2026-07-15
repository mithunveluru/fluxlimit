plugins {
    java
    alias(libs.plugins.spring.boot)
}

description = "FluxLimit in a Spring Boot API: @RateLimit with per-user keys."

dependencies {
    implementation(project(":fluxlimit-spring-boot-starter"))
    implementation(libs.spring.boot.starter.web)
}
