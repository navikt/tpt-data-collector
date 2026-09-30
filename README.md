# tpt-data-collector

![workflow](https://github.com/navikt/tpt-data-collector/actions/workflows/main.yaml/badge.svg)

## Overview

An application that collects data from GitHub and the Cartography database, runs "golden path" checks on it, and sends the results to [tpt-backend](https://github.com/navikt/tpt-backend).

## Endpoints

### `POST /webhook/github`

Receives GitHub webhook events (push events). Protected by HMAC-SHA256 signature validation via the `X-Hub-Signature-256` header.

**Auth:** GitHub webhook secret (`X-Hub-Signature-256` header)

**Request body:** GitHub webhook payload

**Response:** `202 Accepted` — checks run asynchronously; `400 Bad Request` if the payload cannot be parsed

**What it does:** Runs all golden path checks (file checks, GitHub API checks, datastore checks) for the repository that triggered the push, and sends the results to tpt-backend (`POST /callbacks/checks`).

---

### `POST /team/{slug}`

Triggers a full golden path check run for all repositories owned by the given NAIS team.

**Auth:** Azure AD client credentials (`client-credentials-tpt`)

**Path parameter:** `slug` — NAIS team slug (lowercase alphanumeric and hyphens)

**Response:** `202 Accepted` — checks run asynchronously; `400 Bad Request` for an invalid slug

**What it does:** Fetches all repositories for the team from the GitHub API, runs all golden path checks on each repository, and sends one result per repository to tpt-backend (`POST /callbacks/checks`). All pages of the team's repositories are fetched.

---

### `POST /collect/github`

Triggers collection of GitHub vulnerability alert data for a given set of teams and/or repositories. Intended to replace the appsec-stats owned vulnerability pipeline.

**Auth:** Azure AD client credentials (`client-credentials-tpt`)

**Request body:**

```json
{
  "teams": ["team-a", "team-b"],
  "repositories": ["navikt/specific-repo"]
}
```

- `teams` — optional list of NAIS team slugs; repositories are resolved via whodis
- `repositories` — optional list of repos in `owner/name` format to include directly
- At least one of `teams` or `repositories` must be provided; `400 Bad Request` otherwise

**Response:** `202 Accepted` — collection runs asynchronously

**What it does:** Resolves repositories for each team via whodis, merges with any directly specified repos, deduplicates, then for each unique repository fetches all open vulnerability alerts from the GitHub GraphQL API (paginated) and sends them to tpt-backend, one callback per repository. Repositories with no open alerts still produce a callback with an empty `vulnerabilities` list so tpt-backend can clear stale data.

**Callback payload** (`POST /callbacks/github/vulnerabilities`):

```json
{
  "nameWithOwner": "navikt/my-repo",
  "naisTeams": ["team-a", "team-b"],
  "vulnerabilities": [
    {
      "severity": "CRITICAL",
      "identifiers": [{ "value": "CVE-2024-1234", "type": "CVE" }],
      "dependencyScope": "RUNTIME",
      "dependabotUpdatePullRequestUrl": "https://github.com/navikt/my-repo/pull/42",
      "publishedAt": "2024-01-15T00:00:00Z",
      "cvssScore": 9.8,
      "summary": "Remote code execution in some-package",
      "packageEcosystem": "npm",
      "packageName": "some-package"
    }
  ]
}
```

---

### `GET /internal/isAlive`

Liveness probe. Returns `200 OK` when the application is running.

### `GET /internal/isReady`

Readiness probe. Returns `200 OK` when the application is ready to serve traffic.

### `GET /internal/metrics`

Prometheus metrics scrape endpoint.

## Results sent to tpt-backend

All results are delivered as callbacks to [tpt-backend](https://github.com/navikt/tpt-backend) (`TPT_BACKEND_URL`), authenticated with an Entra ID machine-to-machine token for `TPT_BACKEND_TARGET`, fetched from the Nais token endpoint.

| Callback | When |
|---|---|
| `POST /callbacks/github/sync/started` | Before a `/collect/github` run starts. Body: `{"teams": [...], "timestamp": "..."}` |
| `POST /callbacks/github/vulnerabilities` | Once per repository during a `/collect/github` run |
| `POST /callbacks/github/sync/complete` | After a `/collect/github` run. Always sent, also when the run fails |
| `POST /callbacks/checks` | Once per repository for `/team/{slug}` and GitHub webhooks |

Failed callbacks (network errors, `5xx`, `408`, `429`) are retried with exponential backoff. Callbacks that still fail are logged and counted in the `tpt_backend_callbacks_failed` metric, tagged with `callback`.

## License
[MIT](LICENSE).

## Contact

This project is maintained by [@appsec](https://github.com/orgs/navikt/teams/appsec).

Questions and/or feature requests? Please create an [issue](https://github.com/navikt/tpt-data-collector/issues).

If you work in [@navikt](https://github.com/navikt) you can reach us at the Slack channel [#appsec](https://nav-it.slack.com/archives/C06P91VN27M).
