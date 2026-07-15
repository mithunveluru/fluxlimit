plugins {
    application
}

description = "FluxLimit without any framework: one limiter, a few requests."

dependencies {
    implementation(project(":fluxlimit-core"))
}

application {
    mainClass = "example.Main"
}
