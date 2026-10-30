package no.nav.config

class ApplikasjonsConfig(
    val githubAppId: String = fromEnv("GITHUB_APP_ID", "dummy"),
    val githubAppInstallationId: String = fromEnv("GITHUB_APP_INSTALLATION_ID", "dummy"),
    val githubAppPrivateKey: String = fromEnv("GITHUB_APP_PRIVATE_KEY", "dummy"),
    val githubWebhookSecret: String = fromEnv("GITHUB_WEBHOOK_SECRET"),
    val neo4jUri: String = fromEnv("NEO4J_URI", "dummy"),
    val neo4jUser: String = fromEnv("NEO4J_USER", "dummy"),
    val neo4Password: String = fromEnv("NEO4J_PASSWORD", "dummy"),
    val openIdIssuer: String = fromEnv("AZURE_OPENID_CONFIG_ISSUER", "dummy"),
    val openIdAudience: String = fromEnv("AZURE_APP_CLIENT_ID", "dummy"),
    val openIdJwksUri: String = fromEnv("AZURE_OPENID_CONFIG_JWKS_URI", "https://localhost"),
    val whodisUrl: String = fromEnv("WHODIS_URL", "http://whodis"),
    val tptBackendUrl: String = fromEnv("TPT_BACKEND_URL", "http://tpt-backend"),
    val tptBackendTarget: String = fromEnv("TPT_BACKEND_TARGET", "api://prod-gcp.appsec.tpt-backend/.default"),
    val naisTokenEndpoint: String = fromEnv("NAIS_TOKEN_ENDPOINT", "http://localhost/token"),
) {
    init {
        require(githubWebhookSecret.isNotBlank()) {
            "GITHUB_WEBHOOK_SECRET must not be blank"
        }
        val configuredGithubAppValues = listOf(githubAppId, githubAppInstallationId, githubAppPrivateKey)
            .count { !it.isNullOrBlank() }
        if (configuredGithubAppValues in 1..2) {
            throw RuntimeException(
                "GitHub App auth requires GITHUB_APP_ID, GITHUB_APP_INSTALLATION_ID, and GITHUB_APP_PRIVATE_KEY to all be set together",
            )
        }
    }
}

fun fromEnv(
    varName: String,
    defaultValue: String? = null,
) = System.getenv(varName) ?:
    System.getProperty(varName) ?:
    defaultValue ?:
    throw RuntimeException("Missing required variable $varName")
