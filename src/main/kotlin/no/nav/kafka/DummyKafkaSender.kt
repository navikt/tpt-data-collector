package no.nav.kafka

class DummyKafkaSender : KafkaSenderInterface {
    override fun sendToKafka(dataType: String, jsonBlob: String) {}
}