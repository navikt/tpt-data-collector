package no.nav.json

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonifierTest {

    @Test
    fun testJsonify() {
        //when
        val jsonifier = Jsonifier()
        jsonifier.startRow()
        jsonifier.addField("test", "valuee")
        jsonifier.endRow()

        assertEquals("""{"result":[{"test":"valuee"}]}""", jsonifier.finish())
    }

    @Test
    fun testJsonifyEncoding() {
        //when
        val jsonifier = Jsonifier()
        jsonifier.startRow()
        jsonifier.addField("test", "value\"!:;´`'")
        jsonifier.endRow()

        assertEquals("""{"result":[{"test":"value\"!:;´`'"}]}""", jsonifier.finish())
    }
}