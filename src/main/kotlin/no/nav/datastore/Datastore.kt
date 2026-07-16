package no.nav.datastore

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.neo4j.driver.Driver

interface Datastore {
    suspend fun ping(): Boolean
    fun activeDeploymentsFor(originRepo: String): List<Triple<String, String, Instant>>
}

class Neo4jDatastore(val driver: Driver) : Datastore {
    override suspend fun ping(): Boolean {
        return try {
            driver.verifyConnectivity()
            true
        } catch (ex: Exception) {
            ex.printStackTrace()
            false
        }
    }

    override fun activeDeploymentsFor(originRepo: String): List<Triple<String, String, Instant>> {
        val result =
            driver.executableQuery(
                """
                MATCH (:GitHubRepository {name: "$originRepo"})<-[:DEPLOYED_FROM]-(d:NaisDeployment {is_active: true}) RETURN d.team_slug AS team, d.environment_name AS env, d.created_at AS created
            """.trimIndent()
            )
                .execute()
        result.records().forEach(::println)
        return result.records()
            .map {
                Triple(
                    it["team"].asString(),
                    it["env"].asString(),
                    Instant.parse(it["created"].asString())
                )
            }
    }
}

class FakeDatastore : Datastore {
    override suspend fun ping(): Boolean = true

    override fun activeDeploymentsFor(originRepo: String): List<Triple<String, String, Instant>> {
        return when {
            originRepo == "good" -> listOf(Triple("yoloteam", "env1", Clock.System.now()))
            originRepo == "bad" -> listOf(
                Triple("yoloteam", "env1", Clock.System.now()),
                Triple("yoloteam", "env2", Clock.System.now().minus(91.days)),
            )
            else -> emptyList()
        }
    }
}
