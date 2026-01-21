package no.nav.json

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonifierTest {

    @Test
    fun testJsonify() {
        //when
        val jsonifier = Jsonifier("result")
        jsonifier.startRow()
        jsonifier.addField("test", "valuee")
        jsonifier.endRow()

        assertEquals("""{"result":[{"test":"valuee"}]}""", jsonifier.finish())
    }

    @Test
    fun testJsonifyEncoding() {
        //when
        val jsonifier = Jsonifier("encoding")
        jsonifier.startRow()
        jsonifier.addField("test", "value\"!:;´`'")
        jsonifier.endRow()

        assertEquals("""{"encoding":[{"test":"value\"!:;´`'"}]}""", jsonifier.finish())
    }
}