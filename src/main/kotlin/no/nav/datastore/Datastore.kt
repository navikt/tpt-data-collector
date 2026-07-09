package no.nav.datastore

import org.neo4j.driver.Driver

interface Datastore {
    fun containersAbleToRunAsRoot(originRepo: String): List<String>
}

class Neo4jDatastore(val driver: Driver): Datastore {
    override fun containersAbleToRunAsRoot(originRepo: String): List<String> {
        val result = driver.executableQuery("""MATCH (:GitHubRepository {name: "whodis"})<-[*..3]-(c:KubernetesContainer{run_as_non_root: false}) RETURN DISTINCT c.name""").execute()
        return result.records().map { it["name"].asString() }
    }
}

class DummyDatastore: Datastore {
    override fun containersAbleToRunAsRoot(originRepo: String): List<String> {
        return if (originRepo == "good") {
            emptyList()
        } else {
            listOf("boguscontainer")
        }
    }
}