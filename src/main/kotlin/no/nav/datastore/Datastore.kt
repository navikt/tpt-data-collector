package no.nav.datastore

import org.neo4j.driver.Driver

interface Datastore {
    suspend fun ping(): Boolean
    fun containersAbleToRunAsRoot(originRepo: String): List<String>
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

    override fun containersAbleToRunAsRoot(originRepo: String): List<String> {
        val result =
            driver.executableQuery("""MATCH (:GitHubRepository {name: "$originRepo"})<-[*..3]-(c:KubernetesContainer{run_as_non_root: false}) RETURN DISTINCT c.name""")
                .execute()
        return result.records().map { it["name"].asString() }
    }
}

class FakeDatastore : Datastore {
    override suspend fun ping(): Boolean = true

    override fun containersAbleToRunAsRoot(originRepo: String): List<String> {
        return if (originRepo == "good") {
            emptyList()
        } else {
            listOf("boguscontainer")
        }
    }
}