# tpt-data-collector

![workflow](https://github.com/navikt/tpt-data-collector/actions/workflows/main.yaml/badge.svg)

Runs golden path checks and collects GitHub vulnerability alerts for Nav repositories, then sends the results to [tpt-backend](https://github.com/navikt/tpt-backend).

## Quick start

Requires Java 25 ([mise](https://mise.jdx.dev): `mise install`).

```sh
./gradlew test installDist                           # test and build build/install/
build/install/tpt-data-collector/bin/tpt-data-collector  # start on :8080
```

Running the app locally requires real GitHub App credentials, `GITHUB_WEBHOOK_SECRET` and a reachable Neo4j instance (connectivity is verified at startup). The tests use fakes and need none of these.

**Stack:** Kotlin, Ktor, Gradle, Neo4j (Cartography), GitHub App (REST + GraphQL), Nais.

## Configuration

| Variable | Description |
|---|---|
| `GITHUB_APP_ID`, `GITHUB_APP_INSTALLATION_ID`, `GITHUB_APP_PRIVATE_KEY` | GitHub App auth; set all three |
| `GITHUB_WEBHOOK_SECRET` | HMAC secret for `/webhook/github` (required, no default) |
| `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` | Cartography database |
| `WHODIS_URL` | Team/repository ownership lookups |
| `TPT_BACKEND_URL`, `TPT_BACKEND_TARGET` | Callback base URL and Entra ID token audience |

Secrets come from the Nais secrets `tpt-data-collector` and `tpt-graph`. Azure and token endpoint variables are injected by Nais.

## Configuration

`GITHUB_ORGANIZATION` is required and must not be blank. It selects the organization for team repository lookups and webhook filtering. Push webhooks trigger checks only for repositories in this organization on their default branch; organization matching is case-insensitive. There is no implicit organization fallback; the Nais deployment explicitly sets `GITHUB_ORGANIZATION=navikt`.

Each deployment supports one organization for these checks. The `/collect/github` endpoint continues to use explicit `owner/name` repository identities. Configuring another organization does not make Nav-specific infrastructure integrations portable.

Repository identities are qualified `owner/name` values throughout checks, GitHub requests, logs, and callbacks. Webhooks supply GitHub's `full_name`, and team repository listings preserve each repository's `full_name` rather than reconstructing its owner from configuration.

**Breaking integration contracts:** the checks callback uses `nameWithOwner` instead of `repoName`; Whodis ownership lookup uses `/repository/{owner}/{repo}/owners`; and Cartography lookups match `GitHubRepository.full_name`. Whodis team listings must return qualified names. There is no short-name fallback. Deploy only after tpt-backend, Whodis, and Cartography support these contracts.

## Endpoints

| Endpoint | Auth | Does |
|---|---|---|
| `POST /webhook/github` | `X-Hub-Signature-256` | On a push to the default branch of a `navikt` repo: runs GitHub API and datastore checks, plus file checks whose paths were changed (each evaluates the whole repo) |
| `POST /team/{slug}` | Azure AD | Runs all checks on every non-archived repo of the team |
| `POST /collect/github` | Azure AD | Body `{"teams": [...], "repositories": ["owner/name"]}` (at least one). Fetches open vulnerability alerts per repo |
| `GET /internal/isAlive`, `/isReady`, `/metrics` | — | Nais probes and Prometheus metrics |

Work runs in the background: `202 Accepted` means the job started, not that it succeeded. Check the logs and metrics for the outcome.

## Callbacks to tpt-backend

| Callback | When |
|---|---|
| `POST /callbacks/checks` | Once per repo for `/team/{slug}` and webhooks |
| `POST /callbacks/github/sync/started` | Before a `/collect/github` run |
| `POST /callbacks/github/vulnerabilities` | Once per repo, also with an empty list so stale data is cleared ([payload](src/main/kotlin/no/nav/github/GitHubRepositoryMessage.kt)) |
| `POST /callbacks/github/sync/complete` | After a `/collect/github` run, also if it fails |

The checks callback body has `nameWithOwner` (for example, `"navikt/my-repo"`), `repoOwners` (team slugs), and `results` (check results). The previous `repoName` field is no longer sent.

Failed callbacks (network errors, `5xx`, `408`, `429`) are retried with exponential backoff. Callbacks that still fail are logged and counted in the `tpt_backend_callbacks_failed` metric, tagged with `callback`. If file checks cannot read the repository tree or a file, the affected checks are left out of the callback instead of being reported.

## Deployment and operations

Pushes to `main` deploy to `prod-gcp` (namespace `appsec`) via [`main.yaml`](.github/workflows/main.yaml) and [`.nais/nais.yaml`](.nais/nais.yaml). Only `/webhook/github` is exposed publicly.

Logs go to Loki. Notable metrics: `tpt_backend_callbacks_failed{callback}`, `file_checks_skipped{reason}`, `checks_issues_found`, `checks_runtime{type}`.

## Contact

Maintained by [@navikt/appsec](https://github.com/orgs/navikt/teams/appsec). Open an [issue](https://github.com/navikt/tpt-data-collector/issues) or ask in [#appsec](https://nav-it.slack.com/archives/C06P91VN27M) on Slack.

## License

[MIT](LICENSE)
