package no.nav.datastore

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import org.neo4j.driver.Driver

interface Datastore {
    suspend fun ping(): Boolean
    fun activeDeploymentsFor(originRepo: String): List<Triple<String, String, LocalDateTime>>
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

    override fun activeDeploymentsFor(originRepo: String): List<Triple<String, String, LocalDateTime>> {
        val result =
            driver.executableQuery(
                """
                MATCH (:GitHubRepository {name: "$originRepo"})<-[:DEPLOYED_FROM]-(d:NaisDeployment {is_active: true}) RETURN d.team_slug, d.environment_name, d.created_at
            """.trimIndent()
            )
                .execute()
        return result.records()
            .map {
                Triple(
                    it["team_slug"].asString(),
                    it["environment_name"].asString(),
                    it["created_at"].asLocalDateTime()
                )
            }
    }
}

class FakeDatastore : Datastore {
    override suspend fun ping(): Boolean = true

    override fun activeDeploymentsFor(originRepo: String): List<Triple<String, String, LocalDateTime>> {
        return when {
            originRepo == "good" -> listOf(Triple("yoloteam", "env1", LocalDateTime.now()))
            originRepo == "bad" -> listOf(
                Triple("yoloteam", "env1", LocalDateTime.now()),
                Triple("yoloteam", "env2", LocalDateTime.now().atZone(ZoneId.systemDefault()).toLocalDateTime().minusDays(91)),
            )
            else -> emptyList()
        }
    }
}
