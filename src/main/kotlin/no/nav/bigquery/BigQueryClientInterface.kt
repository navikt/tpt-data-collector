package no.nav.bigquery

interface BigQueryClientInterface {
    fun isAlive(): Boolean
    fun readTable(tableName: String): List<Map<String, String>>
}