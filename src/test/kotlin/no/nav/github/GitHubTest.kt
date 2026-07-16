package no.nav.github

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull


class GitHubTest {

    @Test
    fun `token should be refreshed if expires in less than 10 mins`() {
        val now = Clock.System.now()
        val in9Minutes = now.plus(9.minutes)
        assertTrue(needsRefresh(expiresAt = in9Minutes))
    }

    @Test
    fun `token should not be refreshed if expires in more than 10 mins`() {
        val now = Clock.System.now()
        val in11Minutes = now.plus(11.minutes)
        assertFalse(needsRefresh(expiresAt = in11Minutes))
    }

    @Test
    fun `token should be refreshed if expires in less than 10 mins, different timezones`() {
        val nowInUTC = Instant.parse("2016-07-11T10:14:10Z")
        val in9MinsInSydney = Instant.parse("2016-07-11T21:23:10+11:00")
        assertTrue(needsRefresh(nowInUTC, in9MinsInSydney))
    }


    @Test
    fun `token should not be refreshed if expires in more than 10 mins, different timezones`() {
        val nowInUTC = Instant.parse("2016-07-11T10:14:10Z")
        val in11MinsInSydney = Instant.parse("2016-07-11T21:25:10+11:00")
        assertFalse(needsRefresh(nowInUTC, in11MinsInSydney))
    }

    @Test
    fun `Is able to parse security alert response`() {
        val json = Json{ignoreUnknownKeys = true}
        val parsed = json.decodeFromString<List<DependabotAlert>>(securityVulnerabilityJson)
        assertNotNull(parsed)
    }

}

private val securityVulnerabilityJson = """
    [
      {
        "number": 576,
        "state": "open",
        "dependency": {
          "package": {
            "ecosystem": "maven",
            "name": "io.netty:netty-codec-http"
          },
          "manifest_path": "settings.gradle.kts",
          "scope": null,
          "relationship": "direct"
        },
        "security_advisory": {
          "ghsa_id": "GHSA-hvcg-qmg6-jm4c",
          "cve_id": "CVE-2026-50020",
          "summary": "Netty: HttpObjectDecoder skips arbitrary initial control characters when only initial CRLF characters are permitted",
          "description": "bla bla wharever",
          "severity": "medium",
          "identifiers": [
            {
              "value": "GHSA-hvcg-qmg6-jm4c",
              "type": "GHSA"
            },
            {
              "value": "CVE-2026-50020",
              "type": "CVE"
            }
          ],
          "references": [
            {
              "url": "https://github.com/netty/netty/security/advisories/GHSA-hvcg-qmg6-jm4c"
            },
            {
              "url": "https://nvd.nist.gov/vuln/detail/CVE-2026-50020"
            },
            {
              "url": "https://github.com/netty/netty/releases/tag/netty-4.1.135.Final"
            },
            {
              "url": "https://github.com/netty/netty/releases/tag/netty-4.2.15.Final"
            },
            {
              "url": "https://github.com/advisories/GHSA-hvcg-qmg6-jm4c"
            }
          ],
          "published_at": "2026-06-15T20:46:36Z",
          "updated_at": "2026-06-15T20:46:37Z",
          "withdrawn_at": null,
          "vulnerabilities": [
            {
              "package": {
                "ecosystem": "maven",
                "name": "io.netty:netty-codec-http"
              },
              "severity": "medium",
              "vulnerable_version_range": ">= 4.2.0.Final, <= 4.2.14.Final",
              "first_patched_version": {
                "identifier": "4.2.15.Final"
              }
            },
            {
              "package": {
                "ecosystem": "maven",
                "name": "io.netty:netty-codec-http"
              },
              "severity": "medium",
              "vulnerable_version_range": "<= 4.1.134.Final",
              "first_patched_version": {
                "identifier": "4.1.135.Final"
              }
            }
          ],
          "cvss_severities": {
            "cvss_v3": {
              "vector_string": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N",
              "score": 5.3
            },
            "cvss_v4": {
              "vector_string": null,
              "score": 0.0
            }
          },
          "epss": {
            "percentage": 0.00232,
            "percentile": 0.13862
          },
          "cvss": {
            "vector_string": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N",
            "score": 5.3
          },
          "cwes": [
            {
              "cwe_id": "CWE-444",
              "name": "Inconsistent Interpretation of HTTP Requests ('HTTP Request/Response Smuggling')"
            }
          ],
          "classification": "general"
        },
        "security_vulnerability": {
          "package": {
            "ecosystem": "maven",
            "name": "io.netty:netty-codec-http"
          },
          "severity": "medium",
          "vulnerable_version_range": ">= 4.2.0.Final, <= 4.2.14.Final",
          "first_patched_version": {
            "identifier": "4.2.15.Final"
          }
        },
        "url": "https://api.github.com/repos/navikt/etrepo/dependabot/alerts/576",
        "html_url": "https://github.com/navikt/etrepo/security/dependabot/576",
        "created_at": "2026-07-09T12:47:27Z",
        "updated_at": "2026-07-09T12:47:27Z",
        "dismissal_request": null,
        "assignees": [],
        "dismissed_at": null,
        "dismissed_by": null,
        "dismissed_reason": null,
        "dismissed_comment": null,
        "fixed_at": null,
        "auto_dismissed_at": null
      },
      {
        "number": 575,
        "state": "open",
        "dependency": {
          "package": {
            "ecosystem": "maven",
            "name": "io.netty:netty-handler"
          },
          "manifest_path": "settings.gradle.kts",
          "scope": null,
          "relationship": "direct"
        },
        "security_advisory": {
          "ghsa_id": "GHSA-c653-97m9-rcg9",
          "cve_id": "CVE-2026-50010",
          "summary": "Netty: Wrapping plain trust manager silently disables hostname verification",
          "description": "stuff and things",
          "severity": "high",
          "identifiers": [
            {
              "value": "GHSA-c653-97m9-rcg9",
              "type": "GHSA"
            },
            {
              "value": "CVE-2026-50010",
              "type": "CVE"
            }
          ],
          "references": [
            {
              "url": "https://github.com/netty/netty/security/advisories/GHSA-c653-97m9-rcg9"
            },
            {
              "url": "https://nvd.nist.gov/vuln/detail/CVE-2026-50010"
            },
            {
              "url": "https://github.com/netty/netty/releases/tag/netty-4.1.135.Final"
            },
            {
              "url": "https://github.com/netty/netty/releases/tag/netty-4.2.15.Final"
            },
            {
              "url": "https://github.com/advisories/GHSA-c653-97m9-rcg9"
            }
          ],
          "published_at": "2026-06-15T20:45:45Z",
          "updated_at": "2026-06-15T20:45:47Z",
          "withdrawn_at": null,
          "vulnerabilities": [
            {
              "package": {
                "ecosystem": "maven",
                "name": "io.netty:netty-handler"
              },
              "severity": "high",
              "vulnerable_version_range": ">= 4.2.0.Final, < 4.2.15.Final",
              "first_patched_version": {
                "identifier": "4.2.15.Final"
              }
            },
            {
              "package": {
                "ecosystem": "maven",
                "name": "io.netty:netty-handler"
              },
              "severity": "high",
              "vulnerable_version_range": "<= 4.1.134.Final",
              "first_patched_version": {
                "identifier": "4.1.135.Final"
              }
            }
          ],
          "cvss_severities": {
            "cvss_v3": {
              "vector_string": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N",
              "score": 7.5
            },
            "cvss_v4": {
              "vector_string": null,
              "score": 0.0
            }
          },
          "epss": {
            "percentage": 0.00269,
            "percentile": 0.18533
          },
          "cvss": {
            "vector_string": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N",
            "score": 7.5
          },
          "cwes": [
            {
              "cwe_id": "CWE-347",
              "name": "Improper Verification of Cryptographic Signature"
            }
          ],
          "classification": "general"
        },
        "security_vulnerability": {
          "package": {
            "ecosystem": "maven",
            "name": "io.netty:netty-handler"
          },
          "severity": "high",
          "vulnerable_version_range": ">= 4.2.0.Final, < 4.2.15.Final",
          "first_patched_version": {
            "identifier": "4.2.15.Final"
          }
        },
        "url": "https://api.github.com/repos/navikt/repoet/dependabot/alerts/575",
        "html_url": "https://github.com/navikt/repoet/security/dependabot/575",
        "created_at": "2026-07-09T12:47:27Z",
        "updated_at": "2026-07-09T12:47:27Z",
        "dismissal_request": null,
        "assignees": [],
        "dismissed_at": null,
        "dismissed_by": null,
        "dismissed_reason": null,
        "dismissed_comment": null,
        "fixed_at": null,
        "auto_dismissed_at": null
      }
    ]

""".trimIndent()
