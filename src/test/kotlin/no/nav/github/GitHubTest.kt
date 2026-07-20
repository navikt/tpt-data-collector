package no.nav.github

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `Is able to parse repo root response`() {
        val json = Json{ignoreUnknownKeys = true}
        val parsed = json.decodeFromString<RepoRootResponse>(repoRootResponse)
        assertEquals("main", parsed.defaultBranch)
    }

    @Test
    fun `Is able to parse repo tree response`() {
        val json = Json{ignoreUnknownKeys = true}
        val parsed = json.decodeFromString<TreeResponse>(treeResponse)
        assertEquals(42, parsed.tree.size)
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

val repoRootResponse = """
    {
      "name": "whodis",
      "full_name": "navikt/whodis",
      "private": false,
      "owner": {
        "login": "navikt",
      },
      "html_url": "https://github.com/navikt/whodis",
      "description": "Find out who owns repos and/or workloads ",
      "fork": false,
      "url": "https://api.github.com/repos/navikt/whodis",
      "created_at": "2026-04-27T08:19:53Z",
      "updated_at": "2026-07-17T09:43:08Z",
      "pushed_at": "2026-07-20T02:14:51Z",
      "git_url": "git://github.com/navikt/whodis.git",
      "ssh_url": "git@github.com:navikt/whodis.git",
      "clone_url": "https://github.com/navikt/whodis.git",
      "svn_url": "https://github.com/navikt/whodis",
      "homepage": null,
      "size": 180,
      "stargazers_count": 0,
      "watchers_count": 0,
      "language": "Go",
      "has_issues": true,
      "has_projects": true,
      "has_downloads": true,
      "has_wiki": true,
      "has_pages": false,
      "has_discussions": false,
      "forks_count": 0,
      "mirror_url": null,
      "archived": false,
      "disabled": false,
      "open_issues_count": 1,
      "license": {
        "key": "mit",
        "name": "MIT License",
        "spdx_id": "MIT",
        "url": "https://api.github.com/licenses/mit",
        "node_id": "MDc6TGljZW5zZTEz"
      },
      "allow_forking": true,
      "is_template": false,
      "web_commit_signoff_required": false,
      "has_pull_requests": true,
      "pull_request_creation_policy": "all",
      "topics": [],
      "visibility": "public",
      "forks": 0,
      "open_issues": 1,
      "watchers": 0,
      "default_branch": "main",
      "permissions": {
        "admin": true,
        "maintain": true,
        "push": true,
        "triage": true,
        "pull": true
      }
    }

""".trimIndent()

val treeResponse = """
    {
      "sha": "506ab33ac82f1a5bcd9422fa807e8d4c5f909939",
      "url": "https://api.github.com/repos/navikt/whodis/git/trees/506ab33ac82f1a5bcd9422fa807e8d4c5f909939",
      "tree": [
        {
          "path": ".github",
          "mode": "040000",
          "type": "tree",
          "sha": "1b976a8ab9c5ba92217e3e428b374e4c07620373",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/1b976a8ab9c5ba92217e3e428b374e4c07620373"
        },
        {
          "path": ".github/dependabot.yml",
          "mode": "100644",
          "type": "blob",
          "sha": "54e6255c3a93a334b26a9a765518427ac77ccd3a",
          "size": 145,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/54e6255c3a93a334b26a9a765518427ac77ccd3a"
        },
        {
          "path": ".github/workflows",
          "mode": "040000",
          "type": "tree",
          "sha": "7ef76aca5d946216f5d8dba0859145aac9b2d6c4",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/7ef76aca5d946216f5d8dba0859145aac9b2d6c4"
        },
        {
          "path": ".github/workflows/automerge_dependabot.yml",
          "mode": "100644",
          "type": "blob",
          "sha": "9de584ab41bb2f41a4310925c0882c900bb1b241",
          "size": 714,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/9de584ab41bb2f41a4310925c0882c900bb1b241"
        },
        {
          "path": ".github/workflows/main.yaml",
          "mode": "100644",
          "type": "blob",
          "sha": "82550fa9d58857fa7fb736b4898faed5adbcc1a2",
          "size": 1837,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/82550fa9d58857fa7fb736b4898faed5adbcc1a2"
        },
        {
          "path": ".gitignore",
          "mode": "100644",
          "type": "blob",
          "sha": "8fb008b5391b4181b4795d5320d6a9f307086c9f",
          "size": 567,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/8fb008b5391b4181b4795d5320d6a9f307086c9f"
        },
        {
          "path": ".nais",
          "mode": "040000",
          "type": "tree",
          "sha": "b4426d9c38ab12aa61f0ba2288459e963b63c7cc",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/b4426d9c38ab12aa61f0ba2288459e963b63c7cc"
        },
        {
          "path": ".nais/nais.yaml",
          "mode": "100644",
          "type": "blob",
          "sha": "8698be1c7daad8e1eff463ad4d5425eface12173",
          "size": 1083,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/8698be1c7daad8e1eff463ad4d5425eface12173"
        },
        {
          "path": "CODEOWNERS",
          "mode": "100644",
          "type": "blob",
          "sha": "270b360ce298ce500edd67d712c31a51b8ee8e84",
          "size": 17,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/270b360ce298ce500edd67d712c31a51b8ee8e84"
        },
        {
          "path": "Dockerfile",
          "mode": "100644",
          "type": "blob",
          "sha": "46438adde403f168e3b7f9447d38b7649561431a",
          "size": 356,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/46438adde403f168e3b7f9447d38b7649561431a"
        },
        {
          "path": "LICENSE",
          "mode": "100644",
          "type": "blob",
          "sha": "f443ec14b2e5899e89b03c04a0bfc470836e5f9b",
          "size": 1063,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/f443ec14b2e5899e89b03c04a0bfc470836e5f9b"
        },
        {
          "path": "Makefile",
          "mode": "100644",
          "type": "blob",
          "sha": "0554890b5844607250c22204a0b45c5cccb2d489",
          "size": 230,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/0554890b5844607250c22204a0b45c5cccb2d489"
        },
        {
          "path": "README.md",
          "mode": "100644",
          "type": "blob",
          "sha": "059fce3c2274562da709ecf51d02cc0b4a8719b3",
          "size": 2076,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/059fce3c2274562da709ecf51d02cc0b4a8719b3"
        },
        {
          "path": "cmd",
          "mode": "040000",
          "type": "tree",
          "sha": "f7322f58bdeaffc84ab09e8173379a3de3a99e21",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/f7322f58bdeaffc84ab09e8173379a3de3a99e21"
        },
        {
          "path": "cmd/whodis",
          "mode": "040000",
          "type": "tree",
          "sha": "81d8981cad1d4d5925b0334af445ad366c42103f",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/81d8981cad1d4d5925b0334af445ad366c42103f"
        },
        {
          "path": "cmd/whodis/main.go",
          "mode": "100644",
          "type": "blob",
          "sha": "a860672eb41d49382518f587d2a755fc3efc84d6",
          "size": 395,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/a860672eb41d49382518f587d2a755fc3efc84d6"
        },
        {
          "path": "compose.yaml",
          "mode": "100644",
          "type": "blob",
          "sha": "e576e697fbf4954743dd3b0885ef673576888ce6",
          "size": 125,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/e576e697fbf4954743dd3b0885ef673576888ce6"
        },
        {
          "path": "go.mod",
          "mode": "100644",
          "type": "blob",
          "sha": "512f661465cbf5a102f3c51b97c470df626982a0",
          "size": 2240,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/512f661465cbf5a102f3c51b97c470df626982a0"
        },
        {
          "path": "go.sum",
          "mode": "100644",
          "type": "blob",
          "sha": "d1fd125f850718c9f62060ea8e22642dd4c3571f",
          "size": 10368,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/d1fd125f850718c9f62060ea8e22642dd4c3571f"
        },
        {
          "path": "internal",
          "mode": "040000",
          "type": "tree",
          "sha": "4b6f72b7823a74b66dda5564ab89a652504fcf83",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/4b6f72b7823a74b66dda5564ab89a652504fcf83"
        },
        {
          "path": "internal/application",
          "mode": "040000",
          "type": "tree",
          "sha": "44bcf00fa95907f5ca883928e5324505b727a214",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/44bcf00fa95907f5ca883928e5324505b727a214"
        },
        {
          "path": "internal/application/app.go",
          "mode": "100644",
          "type": "blob",
          "sha": "80dc9c06d62578133df5c4d365dc5d0432e7d8af",
          "size": 2671,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/80dc9c06d62578133df5c4d365dc5d0432e7d8af"
        },
        {
          "path": "internal/application/app_test.go",
          "mode": "100644",
          "type": "blob",
          "sha": "6802e1ca57399804048d3166788348976acc6364",
          "size": 761,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/6802e1ca57399804048d3166788348976acc6364"
        },
        {
          "path": "internal/application/routes.go",
          "mode": "100644",
          "type": "blob",
          "sha": "4eaebe39d9c66da6367ac421431985352c612196",
          "size": 2123,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/4eaebe39d9c66da6367ac421431985352c612196"
        },
        {
          "path": "internal/github",
          "mode": "040000",
          "type": "tree",
          "sha": "2f0f8749691de5c18e51e5dba005de13a80864d6",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/2f0f8749691de5c18e51e5dba005de13a80864d6"
        },
        {
          "path": "internal/github/api.go",
          "mode": "100644",
          "type": "blob",
          "sha": "3ded7cc3f3a7ff8e787bd994ed7b4107be4c1aa8",
          "size": 2191,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/3ded7cc3f3a7ff8e787bd994ed7b4107be4c1aa8"
        },
        {
          "path": "internal/github/github.go",
          "mode": "100644",
          "type": "blob",
          "sha": "b32b0bc1e84d47663d276e21b5feaf9d6ba7f6f3",
          "size": 7574,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/b32b0bc1e84d47663d276e21b5feaf9d6ba7f6f3"
        },
        {
          "path": "internal/github/github_test.go",
          "mode": "100644",
          "type": "blob",
          "sha": "6d880240aaee6387811e0732c990f01c55209443",
          "size": 4295,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/6d880240aaee6387811e0732c990f01c55209443"
        },
        {
          "path": "internal/handler",
          "mode": "040000",
          "type": "tree",
          "sha": "88f7df925275a998726190b9da9b765517a6176b",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/88f7df925275a998726190b9da9b765517a6176b"
        },
        {
          "path": "internal/handler/naisteam.go",
          "mode": "100644",
          "type": "blob",
          "sha": "e55bfaeca161c7b11a1e1bc1e913f0ed0e6ea9b7",
          "size": 950,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/e55bfaeca161c7b11a1e1bc1e913f0ed0e6ea9b7"
        },
        {
          "path": "internal/handler/repository.go",
          "mode": "100644",
          "type": "blob",
          "sha": "4e2ad3ea0fa9b1f7a8fa146a7fb14383c48124bd",
          "size": 7575,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/4e2ad3ea0fa9b1f7a8fa146a7fb14383c48124bd"
        },
        {
          "path": "internal/handler/repository_test.go",
          "mode": "100644",
          "type": "blob",
          "sha": "77f878f728fb5595f78a4a3e8802ed641a3d49f0",
          "size": 2131,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/77f878f728fb5595f78a4a3e8802ed641a3d49f0"
        },
        {
          "path": "internal/httpsupport",
          "mode": "040000",
          "type": "tree",
          "sha": "02a8f099d87411bf88bec68a8b043a9252a5f30b",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/02a8f099d87411bf88bec68a8b043a9252a5f30b"
        },
        {
          "path": "internal/httpsupport/httpsupport.go",
          "mode": "100644",
          "type": "blob",
          "sha": "14a64921ac2d1775766cb368638440e7d81e21bb",
          "size": 2857,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/14a64921ac2d1775766cb368638440e7d81e21bb"
        },
        {
          "path": "internal/httpsupport/httpsupport_test.go",
          "mode": "100644",
          "type": "blob",
          "sha": "26a93a6633ce76a16925a9501e461fc6bca98801",
          "size": 1014,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/26a93a6633ce76a16925a9501e461fc6bca98801"
        },
        {
          "path": "internal/nais",
          "mode": "040000",
          "type": "tree",
          "sha": "1a96ee3952af5d35872b6b8f3cbadd7a67ec699d",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/1a96ee3952af5d35872b6b8f3cbadd7a67ec699d"
        },
        {
          "path": "internal/nais/nais.go",
          "mode": "100644",
          "type": "blob",
          "sha": "99458506521974cf820c7589f69bca5eab03fcab",
          "size": 2380,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/99458506521974cf820c7589f69bca5eab03fcab"
        },
        {
          "path": "internal/teamkatalogen",
          "mode": "040000",
          "type": "tree",
          "sha": "b37e38a8e43b0189888d86478b2c009d58254586",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/b37e38a8e43b0189888d86478b2c009d58254586"
        },
        {
          "path": "internal/teamkatalogen/teamkatalogen.go",
          "mode": "100644",
          "type": "blob",
          "sha": "eb2943903ce08aa9bd950e6d814b6f620eddd477",
          "size": 1000,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/eb2943903ce08aa9bd950e6d814b6f620eddd477"
        },
        {
          "path": "mise.toml",
          "mode": "100644",
          "type": "blob",
          "sha": "f3ba969c6a94eaa16c4932601219e6dc13c7bbfe",
          "size": 20,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/f3ba969c6a94eaa16c4932601219e6dc13c7bbfe"
        },
        {
          "path": "testfiles",
          "mode": "040000",
          "type": "tree",
          "sha": "52ddd7eecfb837bad73ac552e8c3e2cc0c2bfdc6",
          "url": "https://api.github.com/repos/navikt/whodis/git/trees/52ddd7eecfb837bad73ac552e8c3e2cc0c2bfdc6"
        },
        {
          "path": "testfiles/private_key.pem",
          "mode": "100644",
          "type": "blob",
          "sha": "880d6c6a514198f59e79efe421b3caf471872f08",
          "size": 3272,
          "url": "https://api.github.com/repos/navikt/whodis/git/blobs/880d6c6a514198f59e79efe421b3caf471872f08"
        }
      ],
      "truncated": false
    }

""".trimIndent()
