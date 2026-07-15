plugins {
    `java-library`
    alias(libs.plugins.jmh)
}

description = "JMH benchmarks. Not published. Run: ./gradlew :benchmarks:jmh"

dependencies {
    jmh(project(":fluxlimit-core"))
    jmh(project(":fluxlimit-redis"))
    jmh(libs.bucket4j.core)
    jmh(libs.resilience4j.ratelimiter)
}

jmh {
    fork = 1
    warmupIterations = 3
    warmup = "2s"
    iterations = 5
    timeOnIteration = "2s"
    profilers.add("gc")
    // redis benchmarks need a local redis: ./gradlew :benchmarks:jmh -PredisBench
    if (!providers.gradleProperty("redisBench").isPresent) {
        excludes.add(".*Redis.*")
    }
}
