package no.nav.kafka

import no.nav.config.KafkaConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaSender: KafkaSenderInterface {
    private val kafkaConfig = KafkaConfig()
    private val kafkaProducer = KafkaProducer<String, String>(kafkaConfig.producerProperties())

    override fun sendToKafka(dataType: String, jsonBlob: String) {
        kafkaProducer.use {
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
