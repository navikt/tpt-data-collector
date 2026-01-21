package no.nav.json

import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import java.io.ByteArrayOutputStream

class Jsonifier {
    val stingStream = ByteArrayOutputStream()
    val jsonGenerator = JsonFactory().createGenerator(stingStream, JsonEncoding.UTF8)

    init {
        jsonGenerator.writeStartObject()
        jsonGenerator.writeFieldName("result")
        jsonGenerator.writeStartArray()
    }

    fun startRow() {
        jsonGenerator.writeStartObject()
    }

    fun endRow() {
        jsonGenerator.writeEndObject()
    }

    fun addField(fieldName: String, value: String) {
        jsonGenerator.writeStringField(fieldName, value)
    }

    fun finish(): String {
        jsonGenerator.writeEndArray()
        jsonGenerator.writeEndObject()
        jsonGenerator.close()
        return stingStream.toString()
    }

}
