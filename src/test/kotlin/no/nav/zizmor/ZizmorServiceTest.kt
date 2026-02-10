package no.nav.zizmor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ZizmorServiceTest {
    @Test
    fun `ZizmorService should throw exception when failing`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        assertThrows<RuntimeException>{ zizmorService.runZizmorOnRepo("navikt", "tpt-data-collector") }
    }

//    @Test
//    fun `ZizmorService should return json when ok`() {
//        val zizmorService = ZizmorService("<REAL_PAT>", "zizmor")
//        val returnString = zizmorService.runZizmorOnRepo("navikt", "tpt-data-collector")
//        assertTrue (returnString.startsWith("["))
//        assertTrue (returnString.endsWith("]"))
//    }

    @Test
    fun `ZizmorService should process result`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        val jsonString = this::class.java.getResource("/zizmor_big_result.json")?.readText() ?: "wops"
        val processed = zizmorService.analyseZizmorResult(jsonString)
        assertEquals(29, processed.results.size)
        assertEquals(13, processed.results.filter { it.ident == "unpinned-uses" }.size)
        assertEquals(5, processed.results.filter { it.ident == "artipacked" }.size)
        assertEquals(7, processed.results.filter { it.ident == "template-injection" }.size)
        assertEquals(4, processed.results.filter { it.ident == "excessive-permissions" }.size)
    }
}