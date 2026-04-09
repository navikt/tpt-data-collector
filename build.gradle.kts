plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.bundles.ktor)
    constraints{
        implementation("io.netty:netty-codec-http:4.2.12.Final") {
            because("Netty HTTP/2 CONTINUATION Frame Flood DoS via Zero-Byte Frame Bypass")
        }
        implementation("io.netty:netty-codec-http2:4.2.12.Final") {
            because("Netty HTTP/2 CONTINUATION Frame Flood DoS via Zero-Byte Frame Bypass")
        }
    }

    implementation(libs.bundles.logging)
    constraints {
        implementation("tools.jackson.core:jackson-core:3.1.1") {
            because("Multiple dependabot vulnerabilities")
        }
    }

    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.datetime)

    // BigQuery
    implementation(libs.bigquery)
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-core:2.21.2") {
            because("jackson-core: Number Length Constraint Bypass in Async Parser Leads to Potential DoS Condition")
        }
    }

    // Kafka
    implementation("at.yawk.lz4:lz4-java:1.10.4")
    implementation("org.apache.kafka:kafka-clients:4.2.0") {
        // "Fikser CVE-2025-12183 - lz4-java >1.8.1 har sårbar versjon (transitive dependency fra kafka-clients:4.1.0)"
        exclude("org.lz4", "lz4-java")
    }

    // Metrics
    // ktor-metrics
    implementation(libs.metrics.ktor)
    implementation(libs.metrics.prometheus)


    //Test
    testImplementation(libs.kotlin.test.junit)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.server.test.host)
}

tasks {
    withType<Jar> {
        archiveBaseName.set("app")

        manifest {
            attributes["Main-Class"] = "no.nav.MainKt"
            attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(separator = " ") {
                it.name
            }
        }

        doLast {
            configurations.runtimeClasspath.get().forEach {
                val file = File("${layout.buildDirectory.get()}/libs/${it.name}")
                if (!file.exists())
                    it.copyTo(file)
            }
        }
    }

    withType<Test> {
        useJUnitPlatform()
        testLogging {
            showExceptions = true
        }
    }

    withType<Wrapper> {
        gradleVersion = "8.14.2"
    }
}
