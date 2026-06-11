# tpt-data-collector

![workflow](https://github.com/navikt/tpt-data-collector/actions/workflows/main.yaml/badge.svg)

## Overview
An application that collects data (from BigQuery and a webhook) and sends aggregated results to tpt.

## Local Dockerfile fetch check

You can verify the new GitHub Dockerfile fetching flow locally without Kafka or BigQuery by running the test-scoped runner task:

```bash
TPT_DATA_COLLECTOR_GITHUB_TOKEN=<token> \
TPT_LOCAL_REPO_OWNER=navikt \
TPT_LOCAL_REPO_NAME=tpt-data-collector \
TPT_LOCAL_REPO_REF=main \
./gradlew runLocalDockerfileCheck
```

Optional variables:

- `TPT_LOCAL_DOCKERFILE_PATH`: fetch only one file instead of discovering all Dockerfile-like paths
- `TPT_LOCAL_REPO_ID`: repo id to include in the generated Kafka payloads, defaults to `0`
- `TPT_DATA_COLLECTOR_GITHUB_USER_AGENT`: overrides the default local runner user agent

The task uses the real `GithubFileClient`, but keeps BigQuery and Kafka in-memory. It prints the discovered candidate paths and any generated `dockerfile_features` payloads to stdout.


## License
[MIT](LICENSE).

## Contact

This project is maintained by [@appsec](https://github.com/orgs/navikt/teams/appsec).

Questions and/or feature requests? Please create an [issue](https://github.com/navikt/tpt-data-collector/issues).

If you work in [@navikt](https://github.com/navikt) you can reach us at the Slack channel [#appsec](https://nav-it.slack.com/archives/C06P91VN27M).

