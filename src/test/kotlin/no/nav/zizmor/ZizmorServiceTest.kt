package no.nav.zizmor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ZizmorServiceTest {
//    @Test
//    fun `ZizmorService should throw exception when failing`() {
//        val zizmorService = ZizmorService("dummy", "zizmor")
//        assertThrows<RuntimeException>{ zizmorService.runZizmorOnRepo("navikt", "tpt-data-collector") }
//    }

//    @Test
//    fun `ZizmorService should return JSON when ok`() {
//        val zizmorService = ZizmorService("<REAL_PAT>", "zizmor")
//        val returnString = zizmorService.runZizmorOnRepo("navikt", "tpt-data-collector")
//        assertTrue (returnString.startsWith("["))
//        assertTrue (returnString.endsWith("]"))
//    }

    @Test
    fun `ZizmorService should process result`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        val jsonString = this::class.java.getResource("/zizmor_big_result.json")?.readText() ?: "wops"
        val processed = zizmorService.analyseZizmorResult("test", jsonString)
        assertEquals("High", processed.severity)
        assertEquals(25, processed.warnings)
        assertEquals(9, processed.results.filter { it.ident == "unpinned-uses" }.size)
        assertEquals(5, processed.results.filter { it.ident == "artipacked" }.size)
        assertEquals(7, processed.results.filter { it.ident == "template-injection" }.size)
        assertEquals(4, processed.results.filter { it.ident == "excessive-permissions" }.size)
    }

    @Test
    fun `ZizmorService should calculate severity correctly ()`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        val jsonString = "[\n" +
                "]\n"
        val processed = zizmorService.analyseZizmorResult("test", jsonString)
        assertEquals("OK", processed.severity)
    }

    @Test
    fun `ZizmorService should calculate severity correctly (Informational)`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        val jsonString = "[\n" +
                makeResult("Informational") +
                "]\n"
        val processed = zizmorService.analyseZizmorResult("test", jsonString)
        assertEquals("Informational", processed.severity)
    }

    @Test
    fun `ZizmorService should calculate severity correctly (Informational, Low)`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        val jsonString = "[\n" +
                makeResult("Informational") + ",\n"+
                makeResult("Low") +
                "]\n"
        val processed = zizmorService.analyseZizmorResult("test", jsonString)
        assertEquals("Low", processed.severity)
    }

    @Test
    fun `ZizmorService should calculate severity correctly (Informational, Medium, Low)`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        val jsonString = "[\n" +
                makeResult("Informational") + ",\n"+
                makeResult("Medium") + ",\n"+
                makeResult("Low") +
                "]\n"
        val processed = zizmorService.analyseZizmorResult("test", jsonString)
        assertEquals("Medium", processed.severity)
    }

    @Test
    fun `ZizmorService should calculate severity correctly (Informational, High, Low, Medium)`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        val jsonString = "[\n" +
                makeResult("Informational") + ",\n"+
                makeResult("High") + ",\n"+
                makeResult("Low") + ",\n"+
                makeResult("Medium") +
                "]\n"
        val processed = zizmorService.analyseZizmorResult("test", jsonString)
        assertEquals("High", processed.severity)
    }

    @Test
    fun `ZizmorService should calculate severity correctly (Low, Medium, High)`() {
        val zizmorService = ZizmorService("dummy", "zizmor")
        val jsonString = "[\n" +
                makeResult("Low") + ",\n"+
                makeResult("Medium") + ",\n"+
                makeResult("High") +
                "]\n"
        val processed = zizmorService.analyseZizmorResult("test", jsonString)
        assertEquals("High", processed.severity)
    }

    private fun makeResult(severity: String): String {
        return "{" +
                "    \"ident\": \"artipacked\",\n" +
                "    \"desc\": \"credential persistence through GitHub Actions artifacts\",\n" +
                "    \"url\": \"https://docs.zizmor.sh/audits/#artipacked\",\n" +
                "    \"determinations\": {\n" +
                "      \"confidence\": \"Low\",\n" +
                "      \"severity\": \"$severity\",\n" +
                "      \"persona\": \"Regular\"\n" +
                "    },\n" +
                "    \"locations\": [\n" +
                "      {\n" +
                "        \"symbolic\": {\n" +
                "          \"key\": {\n" +
                "            \"Remote\": {\n" +
                "              \"slug\": {\n" +
                "                \"owner\": \"navikt\",\n" +
                "                \"repo\": \"reponame\",\n" +
                "                \"git_ref\": null\n" +
                "              },\n" +
                "              \"path\": \".github/workflows/build-and-deploy.yml\"\n" +
                "            }\n" +
                "          },\n" +
                "          \"annotation\": \"does not set persist-credentials: false\",\n" +
                "          \"feature_kind\": \"Normal\",\n" +
                "          \"kind\": \"Primary\"\n" +
                "        },\n" +
                "        \"concrete\": {\n" +
                "          \"location\": {\n" +
                "            \"start_point\": {\n" +
                "              \"row\": 39,\n" +
                "              \"column\": 8\n" +
                "            },\n" +
                "            \"end_point\": {\n" +
                "              \"row\": 40,\n" +
                "              \"column\": 33\n" +
                "            },\n" +
                "            \"offset_span\": {\n" +
                "              \"start\": 1038,\n" +
                "              \"end\": 1128\n" +
                "            }\n" +
                "          },\n" +
                "          \"feature\": \"name: Check-out repository to allow access from workflow\\n        uses: actions/checkout@v4\",\n" +
                "          \"comments\": []\n" +
                "        }\n" +
                "      }\n" +
                "    ],\n" +
                "    \"ignored\": false\n"+
                "}"
    }
}