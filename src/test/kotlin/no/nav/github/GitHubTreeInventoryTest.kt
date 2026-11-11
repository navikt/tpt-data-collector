package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import java.io.StringWriter
import java.security.KeyPairGenerator
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class GitHubTreeInventoryTest {
    companion object {
        private val rootSha = "1".repeat(40)
        private val childSha = "2".repeat(40)
        private val nestedSha = "3".repeat(40)
        private val blobSha = "a".repeat(40)
        private val initial = TreeRequest("main", recursive = true)
        private val root = TreeRequest(rootSha)
        private val child = TreeRequest(childSha)
        private val nested = TreeRequest(nestedSha)
        private val privateKeyPem by lazy {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            StringWriter().also { writer -> JcaPEMWriter(writer).use { it.writeObject(keyPair) } }.toString()
        }

        private fun entry(path: String, type: TreeEntryType = TreeEntryType.BLOB, sha: String = blobSha) =
            TreeEntry(path, sha, type)

        private fun tree(vararg entries: TreeEntry, sha: String = rootSha, truncated: Boolean = false) =
            Json.encodeToString(TreeResponse(sha, truncated, entries.toList()))

        @JvmStatic
        fun malformedResponses(): List<String> = listOf(
            tree().replace(""""truncated":false,""", ""),
            tree().replace(""""sha":"$rootSha",""", ""),
            tree(entry("file")).replace(""""sha":"$blobSha",""", ""),
            tree(entry("file")).replace(""""type":"blob"""", """"type":"unknown"""")
        )

        @JvmStatic
        fun invalidEntries(): List<String> = listOf(
            tree(entry("")),
            tree(entry("../file")),
            tree(entry("/file")),
            tree(entry("dir//file")),
            tree(entry("dir/./file")),
            tree(entry("file", sha = "not-a-sha")),
            tree(entry("same"), entry("same", TreeEntryType.TREE, childSha)),
            tree(sha = "")
        )
    }

    private data class TreeRequest(val ref: String, val recursive: Boolean = false)
    private data class Response(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)
    private data class Fixture(
        val inventory: GitHubTreeInventory,
        val client: HttpClient,
        val requests: List<TreeRequest>
    ) {
        fun realGitHub() = RealGitHub(client, "123", "456", privateKeyPem)
    }

    private suspend fun withInventory(
        responses: Map<TreeRequest, Response>,
        limits: TreeInventoryLimits = TreeInventoryLimits(),
        beforeResponse: suspend (TreeRequest) -> Unit = {},
        block: suspend (Fixture) -> Unit
    ) {
        val requests = mutableListOf<TreeRequest>()
        val engine = MockEngine { request ->
            val response = when (request.url.encodedPath) {
                "/app/installations/456/access_tokens" ->
                    Response("""{"token":"test-token","expires_at":"2099-01-01T00:00:00Z"}""")
                "/repos/navikt/repo" -> Response("""{"default_branch":"main"}""")
                else -> {
                    val treeRequest = TreeRequest(
                        request.url.encodedPath.substringAfter("/git/trees/"),
                        request.url.parameters["recursive"] != null
                    )
                    requests += treeRequest
                    beforeResponse(treeRequest)
                    responses.getValue(treeRequest)
                }
            }
            respond(
                response.body, response.status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }.use { client ->
            val inventory = GitHubTreeInventory("repo", limits) { ref, recursive ->
                val url = "https://api.github.com/repos/navikt/repo/git/trees/$ref" +
                    if (recursive) "?recursive=true" else ""
                client.get(url).body<TreeResponse>()
            }
            block(Fixture(inventory, client, requests))
        }
    }

    @Test
    fun `complete recursive trees preserve paths without extra requests`() = runBlocking {
        val paths = listOf(".github", ".github/workflows/check.yml", "symlink", "submodule")
        withInventory(mapOf(initial to Response(tree(
            entry(paths[0], TreeEntryType.TREE, childSha),
            entry(paths[1]),
            entry(paths[2]),
            entry(paths[3], TreeEntryType.COMMIT, nestedSha)
        )))) { fixture ->
            assertEquals(paths, fixture.inventory.paths("main"))
            assertEquals(listOf(initial), fixture.requests)
        }
    }

    @Test
    fun `truncated trees use immutable SHAs and expand cached subtrees under every parent`() = runBlocking {
        withInventory(mapOf(
            initial to Response(tree(entry("discarded.txt"), truncated = true)),
            root to Response(tree(
                entry("left", TreeEntryType.TREE, childSha),
                entry("right", TreeEntryType.TREE, childSha),
                entry("submodule", TreeEntryType.COMMIT, rootSha)
            )),
            child to Response(tree(
                entry("file.txt"),
                entry("nested", TreeEntryType.TREE, nestedSha),
                sha = childSha
            )),
            nested to Response(tree(entry("deep.txt"), sha = nestedSha))
        )) { fixture ->
            assertEquals(
                listOf("left", "left/file.txt", "left/nested", "left/nested/deep.txt",
                    "right", "right/file.txt", "right/nested", "right/nested/deep.txt", "submodule"),
                fixture.inventory.paths("main")
            )
            assertEquals(initial, fixture.requests.first())
            assertEquals(1, fixture.requests.count { it == child })
            assertEquals(1, fixture.requests.count { it == nested })
            assertEquals(setOf(initial, root, child, nested), fixture.requests.toSet())
            assertEquals(4, fixture.requests.size)
        }
    }

    @Test
    fun `deduplicates identical entries`() = runBlocking {
        withInventory(mapOf(
            initial to Response(tree(truncated = true)),
            root to Response(tree(entry("dir", TreeEntryType.TREE, childSha), entry("dir", TreeEntryType.TREE, childSha))),
            child to Response(tree(entry("file"), entry("file"), sha = childSha))
        )) { fixture ->
            assertEquals(listOf("dir", "dir/file"), fixture.inventory.paths("main"))
            assertEquals(listOf(initial, root, child), fixture.requests)
        }
    }

    @Test
    fun `rejects truncated non-recursive trees`() = runBlocking {
        withInventory(mapOf(
            initial to Response(tree(truncated = true)),
            root to Response(tree(entry("partial"), truncated = true))
        )) { fixture ->
            val failure = assertThrows<GitHubFileInventoryException> { fixture.inventory.paths("main") }
            assertTrue(failure.message!!.contains("navikt/repo"))
            assertTrue(failure.message!!.contains("non-recursive tree response is truncated"))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [403, 404, 409, 500])
    fun `later subtree failures never return earlier paths or turn into empty success`(status: Int) = runBlocking {
        withInventory(mapOf(
            initial to Response(tree(truncated = true)),
            root to Response(tree(entry("already-listed"), entry("dir", TreeEntryType.TREE, childSha))),
            child to Response("""{"message":"Git Repository is empty."}""", HttpStatusCode.fromValue(status))
        )) { fixture ->
            assertThrows<io.ktor.client.plugins.ResponseException> { fixture.inventory.paths("main") }
            assertEquals(listOf(initial, root, child), fixture.requests)
        }
    }

    @ParameterizedTest
    @MethodSource("malformedResponses")
    fun `rejects responses with missing completeness fields or malformed entry types`(response: String) = runBlocking {
        withInventory(mapOf(initial to Response(response))) { fixture ->
            assertThrows<JsonConvertException> { fixture.inventory.paths("main") }
        }
    }

    @ParameterizedTest
    @MethodSource("invalidEntries")
    fun `rejects invalid paths SHAs and conflicting entries`(response: String) = runBlocking {
        withInventory(mapOf(initial to Response(response))) { fixture ->
            assertThrows<GitHubFileInventoryException> { fixture.inventory.paths("main") }
        }
    }

    @Test
    fun `non-recursive entries must be direct children`() = runBlocking {
        withInventory(mapOf(
            initial to Response(tree(truncated = true)),
            root to Response(tree(entry("unexpected/nested")))
        )) { fixture ->
            assertThrows<GitHubFileInventoryException> { fixture.inventory.paths("main") }
        }
    }

    @Test
    fun `rejects mismatched child tree SHAs`() = runBlocking {
        withInventory(mapOf(
            initial to Response(tree(truncated = true)),
            root to Response(tree(entry("dir", TreeEntryType.TREE, childSha))),
            child to Response(tree(entry("file"), sha = nestedSha))
        )) { fixture ->
            assertThrows<GitHubFileInventoryException> { fixture.inventory.paths("main") }
        }
    }

    @Test
    fun `rejects tree cycles without expanding them`() = runBlocking {
        withInventory(mapOf(
            initial to Response(tree(truncated = true)),
            root to Response(tree(entry("cycle", TreeEntryType.TREE, rootSha)))
        )) { fixture ->
            val failure = assertThrows<GitHubFileInventoryException> { fixture.inventory.paths("main") }
            assertTrue(failure.message!!.contains("tree cycle"))
            assertEquals(listOf(initial, root), fixture.requests)
        }
    }

    @Test
    fun `tree fetch budget includes the initial recursive request and stops before another fetch`() = runBlocking {
        withInventory(
            mapOf(
                initial to Response(tree(truncated = true)),
                root to Response(tree(entry("dir", TreeEntryType.TREE, childSha)))
            ),
            limits = TreeInventoryLimits(maxTreeFetches = 2)
        ) { fixture ->
            val failure = assertThrows<GitHubFileInventoryException> { fixture.inventory.paths("main") }
            assertTrue(failure.message!!.contains("tree fetch budget"))
            assertEquals(listOf(initial, root), fixture.requests)
        }
    }

    @Test
    fun `exact tree fetch and expanded entry budgets permit a complete result`() = runBlocking {
        withInventory(
            mapOf(
                initial to Response(tree(truncated = true)),
                root to Response(tree(entry("dir", TreeEntryType.TREE, childSha))),
                child to Response(tree(entry("file"), sha = childSha))
            ),
            limits = TreeInventoryLimits(maxTreeFetches = 3, maxEntries = 2)
        ) { fixture ->
            assertEquals(listOf("dir", "dir/file"), fixture.inventory.paths("main"))
        }
    }

    @Test
    fun `entry budget counts cached subtree expansion under each path`() = runBlocking {
        withInventory(
            mapOf(
                initial to Response(tree(truncated = true)),
                root to Response(tree(
                    entry("left", TreeEntryType.TREE, childSha),
                    entry("right", TreeEntryType.TREE, childSha)
                )),
                child to Response(tree(entry("one"), entry("two"), sha = childSha))
            ),
            limits = TreeInventoryLimits(maxEntries = 5)
        ) { fixture ->
            val failure = assertThrows<GitHubFileInventoryException> { fixture.inventory.paths("main") }
            assertTrue(failure.message!!.contains("expanded entry budget"))
            assertEquals(1, fixture.requests.count { it == child })
        }
    }

    @Test
    fun `complete recursive trees also respect the entry budget`() = runBlocking {
        withInventory(
            mapOf(initial to Response(tree(entry("one"), entry("two")))),
            limits = TreeInventoryLimits(maxEntries = 1)
        ) { fixture ->
            assertThrows<GitHubFileInventoryException> { fixture.inventory.paths("main") }
        }
    }

    @Test
    fun `explicit empty repository response is an empty success`() = runBlocking {
        withInventory(mapOf(initial to Response(
            """{"message":"Git Repository is empty.","documentation_url":"https://docs.github.com/rest/git/trees"}""",
            HttpStatusCode.Conflict
        ))) { fixture ->
            assertTrue(fixture.inventory.paths("main").isEmpty())
            assertEquals(listOf(initial), fixture.requests)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", """{"message":"Another conflict"}""", "not-json"])
    fun `ordinary or malformed conflict responses remain failures`(response: String) = runBlocking {
        withInventory(mapOf(initial to Response(response, HttpStatusCode.Conflict))) { fixture ->
            assertThrows<ClientRequestException> { fixture.inventory.paths("main") }
        }
    }

    @Test
    fun `empty complete trees are successful`() = runBlocking {
        withInventory(mapOf(initial to Response(tree()))) { fixture ->
            assertTrue(fixture.inventory.paths("main").isEmpty())
        }
    }

    @Test
    fun `RealGitHub wires metadata lookup and subtree fallback together`() = runBlocking {
        withInventory(mapOf(
            initial to Response(tree(entry("discarded"), truncated = true)),
            root to Response(tree(entry("dir", TreeEntryType.TREE, childSha))),
            child to Response(tree(entry("file"), sha = childSha))
        )) { fixture ->
            assertEquals(listOf("dir", "dir/file"), fixture.realGitHub().allFilePathsIn("repo"))
            assertEquals(listOf(initial, root, child), fixture.requests)
        }
    }

    @Test
    fun `inventory deadline cancels suspended work and surfaces an inventory failure`() = runBlocking {
        var fetchCancelled = false
        withInventory(emptyMap(), beforeResponse = {
            try {
                awaitCancellation()
            } finally {
                fetchCancelled = true
            }
        }) { fixture ->
            val gitHub = fixture.realGitHub()
            val failure = assertThrows<GitHubFileInventoryException> {
                gitHub.allFilePathsIn("repo", TreeInventoryLimits(timeout = 500.milliseconds))
            }
            assertTrue(failure.message!!.contains("time budget"))
            assertTrue(fetchCancelled)
        }
    }

    @Test
    fun `parent cancellation is not converted into an inventory failure`() = runBlocking {
        withTimeout(5.seconds) {
            val started = CompletableDeferred<Unit>()
            withInventory(emptyMap(), beforeResponse = {
                started.complete(Unit)
                awaitCancellation()
            }) { fixture ->
                val gitHub = fixture.realGitHub()
                val work = async { gitHub.allFilePathsIn("repo") }
                started.await()
                work.cancel()
                assertThrows<CancellationException> { work.await() }
                assertFalse(work.isActive)
            }
        }
    }
}
