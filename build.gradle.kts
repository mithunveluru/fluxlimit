import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.errorprone) apply false
    alias(libs.plugins.maven.publish) apply false
}

// catalog accessors resolve against the subproject inside subprojects {}
val errorproneCore = libs.errorprone.core

// shared build logic inline, no buildSrc
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "net.ltgt.errorprone")

    group = "io.github.mithunveluru"
    version = "0.2.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        "errorprone"(errorproneCore)
    }

    configure<SpotlessExtension> {
        java {
            googleJavaFormat()
            target("src/**/*.java")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.errorprone.disableWarningsInGeneratedCode = true
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        finalizedBy(tasks.withType<JacocoReport>())
    }

    tasks.withType<JacocoReport>().configureEach {
        reports.xml.required = true
    }
}

// benchmarks and examples stay unpublished
configure(subprojects.filter { it.name.startsWith("fluxlimit-") }) {
    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
        // signing only when release keys are configured
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }
        coordinates("io.github.mithunveluru", name, version.toString())
        pom {
            name.set(project.name)
            description.set(provider { project.description ?: "Distributed API rate limiting for Java" })
            url.set("https://github.com/mithunveluru/fluxlimit")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("mithunveluru")
                    name.set("Mithun Veluru")
                }
            }
            scm {
                url.set("https://github.com/mithunveluru/fluxlimit")
                connection.set("scm:git:git://github.com/mithunveluru/fluxlimit.git")
                developerConnection.set("scm:git:ssh://git@github.com/mithunveluru/fluxlimit.git")
            }
        }
    }
}
