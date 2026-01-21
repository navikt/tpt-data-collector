package no.nav.bigquery

interface BigQueryClientInterface {
    fun readTable(tableName: String): String
}