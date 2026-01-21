package no.nav.bigquery

class DummyBigQuery: BigQueryClientInterface {
    override fun readTable(tableName: String): String {
        return "{\"$tableName\":[]}"
    }

}
