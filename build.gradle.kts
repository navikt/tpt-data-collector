version = "notimportant"

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.cyclonedx.bom") version "3.3.0"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.bundles.ktor)

    implementation(libs.bundles.logging)

    implementation(libs.kotlinx.serialization)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.nimbus.jose)

    // Kafka
    implementation(libs.kafka)

    // Metrics
    implementation(libs.metrics.ktor)
    implementation(libs.metrics.prometheus)

    // Neo4j
    implementation(libs.neo4j)

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
        gradleVersion = "9.6.1"
    }
}

