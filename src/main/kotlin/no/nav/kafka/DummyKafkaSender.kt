package no.nav.kafka

class DummyKafkaSender : KafkaSenderInterface {
    val sentMessages = mutableListOf<Pair<String, String>>()

    override fun sendToKafka(dataType: String, jsonBlob: String) {
        sentMessages += dataType to jsonBlob
    }
}