package no.nav.datastore

import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.neo4j.driver.EagerResult
import org.neo4j.driver.ExecutableQuery

class Neo4jDatastoreTest {
    @Test
    fun `deployment lookup matches full_name and binds qualified repository identities`() {
        val queries = mutableListOf<String>()
        val parameters = mutableListOf<Any?>()
        val result = stub(EagerResult::class.java) { method, _ ->
            when (method) {
                "records" -> emptyList<org.neo4j.driver.Record>()
                else -> error("Unexpected result method: $method")
            }
        }
        lateinit var query: ExecutableQuery
        query = stub(ExecutableQuery::class.java) { method, args ->
            when (method) {
                "withParameters" -> {
                    parameters += args.single()
                    query
                }
                "execute" -> result
                else -> error("Unexpected query method: $method")
            }
        }
        val driver = stub(Driver::class.java) { method, args ->
            when (method) {
                "executableQuery" -> {
                    queries += args.single() as String
                    query
                }
                else -> error("Unexpected driver method: $method")
            }
        }

        val datastore = Neo4jDatastore(driver)
        assertEquals(emptyList<Any>(), datastore.activeDeploymentsFor(nameWithOwner = "navikt/shared"))
        assertEquals(emptyList<Any>(), datastore.activeDeploymentsFor(nameWithOwner = "other-owner/shared"))
        val expectedQuery =
            $$"MATCH (:GitHubRepository {full_name: $repo})<-[:DEPLOYED_FROM]-(d:NaisDeployment {is_active: true}) RETURN d.team_slug AS team, d.environment_name AS env, d.created_at AS created"
        assertEquals(listOf(expectedQuery, expectedQuery), queries)
        assertEquals(
            listOf(mapOf("repo" to "navikt/shared"), mapOf("repo" to "other-owner/shared")),
            parameters
        )
    }

    private fun <T> stub(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        })
}
