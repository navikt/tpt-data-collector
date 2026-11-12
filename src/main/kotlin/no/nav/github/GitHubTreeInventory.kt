package no.nav.github

import io.ktor.client.plugins.ClientRequestException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.yield

internal data class TreeInventoryLimits(
    val maxTreeFetches: Int = 250,
    val maxEntries: Int = 1_000_000,
    val timeout: Duration = 60.seconds
) {
    init {
        require(maxTreeFetches > 0)
        require(maxEntries > 0)
        require(timeout.isPositive() && timeout.isFinite())
    }
}

internal class GitHubTreeInventory(
    private val repoName: String,
    private val limits: TreeInventoryLimits,
    private val fetchTree: suspend (ref: String, recursive: Boolean) -> TreeResponse
) {
    private var treeFetches = 0
    private var expandedEntries = 0
    private val shaPattern = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")

    suspend fun paths(defaultBranch: String): List<String> {
        val recursiveTree = try {
            fetch(defaultBranch, recursive = true)
        } catch (ex: ClientRequestException) {
            if (!ex.response.isEmptyGitHubRepository()) throw ex
            return emptyList()
        } catch (_: GitHubEmptyRepositoryException) {
            return emptyList()
        }
        val initialEntries = validate(recursiveTree, recursive = true)
        if (!recursiveTree.truncated) {
            return initialEntries.map { it.path }
        }

        // A truncated response cannot seed the cache: even its returned subtrees may be incomplete.
        val cache = mutableMapOf<String, List<TreeEntry>>()
        suspend fun entriesFor(sha: String): List<TreeEntry> =
            cache[sha] ?: run {
                val tree = fetch(sha, recursive = false)
                if (tree.sha != sha) fail("tree response SHA does not match the requested SHA")
                if (tree.truncated) fail("non-recursive tree response is truncated")
                validate(tree, recursive = false).also { cache[sha] = it }
            }

        val paths = linkedSetOf<String>()
        val pending = ArrayDeque<PendingTree>()
        pending.addLast(PendingTree("", setOf(recursiveTree.sha), entriesFor(recursiveTree.sha).iterator()))
        while (pending.isNotEmpty()) {
            yield()
            val current = pending.last()
            if (!current.entries.hasNext()) {
                pending.removeLast()
                continue
            }
            val entry = current.entries.next()
            if (++expandedEntries > limits.maxEntries) fail("expanded entry budget exceeded")
            val path = if (current.prefix.isEmpty()) entry.path else "${current.prefix}/${entry.path}"
            paths.add(path)
            if (entry.type == TreeEntryType.TREE) {
                if (entry.sha in current.ancestors) fail("tree cycle detected")
                pending.addLast(PendingTree(path, current.ancestors + entry.sha, entriesFor(entry.sha).iterator()))
            }
        }
        return paths.toList()
    }

    private suspend fun fetch(ref: String, recursive: Boolean): TreeResponse {
        if (++treeFetches > limits.maxTreeFetches) fail("tree fetch budget exceeded")
        return fetchTree(ref, recursive)
    }

    private suspend fun validate(tree: TreeResponse, recursive: Boolean): List<TreeEntry> {
        if (!shaPattern.matches(tree.sha)) fail("invalid root tree SHA")
        if (tree.tree.size > limits.maxEntries) fail("tree entry budget exceeded")
        val entries = linkedMapOf<String, TreeEntry>()
        for ((index, entry) in tree.tree.withIndex()) {
            if (index % 256 == 0) yield()
            if (!shaPattern.matches(entry.sha)) fail("invalid tree entry SHA")
            if (entry.path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                '\u0000' in entry.path || (!recursive && '/' in entry.path)
            ) {
                fail("invalid tree entry path")
            }
            val previous = entries.put(entry.path, entry)
            if (previous != null && previous != entry) fail("conflicting entries for the same path")
        }
        return entries.values.toList()
    }

    private fun fail(reason: String): Nothing = throw GitHubFileInventoryException(repoName, reason)

    private data class PendingTree(
        val prefix: String,
        val ancestors: Set<String>,
        val entries: Iterator<TreeEntry>
    )
}

internal class GitHubFileInventoryException(repoName: String, reason: String) :
    IllegalStateException("Incomplete GitHub file inventory for $repoName: $reason")

internal class GitHubEmptyRepositoryException : IllegalStateException("GitHub repository is empty")
