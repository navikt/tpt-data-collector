package no.nav.bigquery

class DummyBigQuery : BigQueryClientInterface {
    override fun isAlive(): Boolean {
        return true
    }
    override fun readTable(tableName: String): List<Map<String, String>> {
        return emptyList()
    }
}
