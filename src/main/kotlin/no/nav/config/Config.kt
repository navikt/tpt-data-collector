package no.nav.config

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringSerializer

class KafkaConfig(
    val brokers: String = getEnvVar("KAFKA_BROKERS", "brokers"),
    val truststoreLocation: String = getEnvVar("KAFKA_TRUSTSTORE_PATH", ""),
    val keystoreLocation: String = getEnvVar("KAFKA_KEYSTORE_PATH", ""),
    val credstorePassword: String = getEnvVar("KAFKA_CREDSTORE_PASSWORD", ""),
    val clientId: String = getEnvVar("KAFKA_CLIENT_ID", "clientId"),
    val tptTopic: String = getEnvVar("TPT_TOPIC"),
) {
    fun producerProperties(): Map<String, Any> {
        val producerConfigs = mutableMapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to brokers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true, // Safe order
            ProducerConfig.ACKS_CONFIG to "all", // Safe data
            ProducerConfig.CLIENT_ID_CONFIG to clientId,
        )
        if (truststoreLocation.isNotEmpty()) {
            producerConfigs.putAll(securityConfigs())
        }
        return producerConfigs.toMap()
    }

    fun securityConfigs() =
        mapOf(
            CommonClientConfigs.SECURITY_PROTOCOL_CONFIG to "SSL",
            SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG to "",
            SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG to "JKS",
            SslConfigs.SSL_KEYSTORE_TYPE_CONFIG to "PKCS12",
            SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG to truststoreLocation,
            SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG to credstorePassword,
            SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG to keystoreLocation,
            SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG to credstorePassword,
            SslConfigs.SSL_KEY_PASSWORD_CONFIG to credstorePassword,
        )
}

class ApplikasjonsConfig(
    val githubAppId: String? = getEnvVar("GITHUB_APP_ID", "dummy"),
    val githubAppInstallationId: String? = getEnvVar("GITHUB_APP_INSTALLATION_ID", "dummy"),
    val githubAppPrivateKey: String? = getEnvVar("GITHUB_APP_PRIVATE_KEY", "dummy"),
    val githubWebhookSecret: String = getEnvVar("GITHUB_WEBHOOK_SECRET", "dummy"),
    val neo4jUri: String = getEnvVar("NEO4J_URI", "dummy"),
    val neo4jUser: String = getEnvVar("NEO4J_USER", "dummy"),
    val neo4Password: String = getEnvVar("NEO4J_PASSWORD", "dummy"),
) {
    init {
        val configuredGithubAppValues = listOf(githubAppId, githubAppInstallationId, githubAppPrivateKey)
            .count { !it.isNullOrBlank() }
        if (configuredGithubAppValues in 1..2) {
            throw RuntimeException(
                "GitHub App auth requires GITHUB_APP_ID, GITHUB_APP_INSTALLATION_ID, and GITHUB_APP_PRIVATE_KEY to all be set together",
            )
        }
    }
}

fun getEnvVar(
    varName: String,
    defaultValue: String? = null,
) = System.getenv(varName) ?: defaultValue ?: throw RuntimeException("Missing required variable $varName")

fun getOptionalEnvVar(varName: String): String? = System.getenv(varName)
