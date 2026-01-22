package no.nav.kafka

import no.nav.config.KafkaConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaSender: KafkaSenderInterface {
    private val kafkaConfig = KafkaConfig()

    override fun sendToKafka(dataType: String, jsonBlob: String) {
        KafkaProducer<String, String>(
            kafkaConfig.producerProperties(),
        ).use {
            it.send(
                ProducerRecord(
                    kafkaConfig.tptTopic,
                    dataType,
                    jsonBlob,
                ),
            )
        }
    }
}
