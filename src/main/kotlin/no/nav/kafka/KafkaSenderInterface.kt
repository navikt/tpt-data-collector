package no.nav.kafka

interface KafkaSenderInterface {

    fun sendToKafka(dataType: String, jsonBlob: String)
}
