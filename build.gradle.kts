plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("no.nav.MainKt")
}

dependencies {
    implementation(libs.bundles.ktor)

    implementation(libs.bundles.logging)
    runtimeOnly(libs.log4j.jpl)

    implementation(libs.kotlinx.serialization)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.nimbus.jose)

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
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
    }
}
