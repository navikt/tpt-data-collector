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
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true, // Den sikrer rekkefølge
            ProducerConfig.ACKS_CONFIG to "all", // Den sikrer at data ikke mistes
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
    val projectId: String = getEnvVar("GCP_TEAM_PROJECT_ID", "appsec"),
    val datasetName: String = getEnvVar("BIGQUERY_DATASET_ID", "appsec"),
    val githubToken: String = getEnvVar("GITHUB_TOKEN", "dummy"),
)

fun getEnvVar(
    varName: String,
    defaultValue: String? = null,
) = System.getenv(varName) ?: defaultValue ?: throw RuntimeException("Missing required variable $varName")
