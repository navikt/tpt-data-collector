package no.nav.github

import java.net.URI
import java.net.URISyntaxException

internal class GitHubPaginationException(reason: String) :
    IllegalStateException("Incomplete GitHub repository collection: $reason")

internal class GitHubPagination {
    private val visited = mutableSetOf<String>()

    fun visit(url: String): String {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            throw GitHubPaginationException("malformed page URL")
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("api.github.com", ignoreCase = true) ||
            (uri.port != -1 && uri.port != 443) ||
            uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.rawPath.isNullOrEmpty()
        ) {
            throw GitHubPaginationException("page URL is not an allowed GitHub API URL")
        }
        val normalized = uri.normalize()
        val key = normalized.rawPath + (normalized.rawQuery?.let { "?$it" } ?: "")
        if (!visited.add(key)) {
            throw GitHubPaginationException("page URL did not advance")
        }
        return uri.toASCIIString()
    }
}

private const val parameterName = "[!#$%&'*+.^_`|~0-9A-Za-z-]+"
private const val parameterValue = "\"[^\"\\r\\n]*\"|[^\",;\\s]+"
private val linkPattern = Regex("\\s*<([^<>\\s]+)>\\s*((?:;\\s*$parameterName\\s*=\\s*(?:$parameterValue)\\s*)*)")
private val parameterPattern = Regex(";\\s*($parameterName)\\s*=\\s*($parameterValue)")

// Match complete link values so commas in a URL or quoted parameter are not treated as separators.
internal fun nextPageUrl(linkHeader: String?): String? {
    if (linkHeader == null) return null
    var offset = 0
    var next: String? = null
    do {
        val link = linkPattern.matchAt(linkHeader, offset)
            ?: throw GitHubPaginationException("malformed Link header")
        val relations = parameterPattern.findAll(link.groupValues[2])
            .filter { it.groupValues[1].equals("rel", ignoreCase = true) }
            .map { it.groupValues[2].removeSurrounding("\"") }
            .toList()
        if (relations.size > 1) throw GitHubPaginationException("ambiguous link relation")
        if (relations.singleOrNull()?.split(Regex("\\s+"))?.contains("next") == true) {
            if (next != null) throw GitHubPaginationException("multiple next links")
            next = link.groupValues[1]
        }
        offset = link.range.last + 1
        if (offset == linkHeader.length) break
        if (linkHeader[offset] != ',' || ++offset == linkHeader.length) {
            throw GitHubPaginationException("malformed Link header")
        }
    } while (true)
    return next
}
