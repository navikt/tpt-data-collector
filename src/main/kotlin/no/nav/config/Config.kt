package no.nav.config

class ApplikasjonsConfig(
    val githubAppId: String = getEnvVar("GITHUB_APP_ID", "dummy"),
    val githubAppInstallationId: String = getEnvVar("GITHUB_APP_INSTALLATION_ID", "dummy"),
    val githubAppPrivateKey: String = getEnvVar("GITHUB_APP_PRIVATE_KEY", "dummy"),
    val githubWebhookSecret: String = getEnvVar("GITHUB_WEBHOOK_SECRET", "dummy"),
    val neo4jUri: String = getEnvVar("NEO4J_URI", "dummy"),
    val neo4jUser: String = getEnvVar("NEO4J_USER", "dummy"),
    val neo4Password: String = getEnvVar("NEO4J_PASSWORD", "dummy"),
    val openIdIssuer: String = getEnvVar("AZURE_OPENID_CONFIG_ISSUER", "dummy"),
    val openIdAudience: String = getEnvVar("AZURE_APP_CLIENT_ID", "dummy"),
    val openIdJwksUri: String = getEnvVar("AZURE_OPENID_CONFIG_JWKS_URI", "https://localhost"),
    val whodisUrl: String = getEnvVar("WHODIS_URL", "http://whodis"),
    val tptBackendUrl: String = getEnvVar("TPT_BACKEND_URL", "http://tpt-backend"),
    val tptBackendTarget: String = getEnvVar("TPT_BACKEND_TARGET", "api://prod-gcp.appsec.tpt-backend/.default"),
    val naisTokenEndpoint: String = getEnvVar("NAIS_TOKEN_ENDPOINT", "http://localhost/token"),
) {
    init {
        val configuredGithubAppValues = listOf(githubAppId, githubAppInstallationId, githubAppPrivateKey)
            .count { !it.isNullOrBlank() }
        if (configuredGithubAppValues in 1..2) {
            throw RuntimeException(
                "GitHub App auth requires GITHUB_APP_ID, GITHUB_APP_INSTALLATION_ID, and GITHUB_APP_PRIVATE_KEY to all be set together",
            )
        }
    }
}

fun getEnvVar(
    varName: String,
    defaultValue: String? = null,
) = System.getenv(varName) ?: defaultValue ?: throw RuntimeException("Missing required variable $varName")

fun getOptionalEnvVar(varName: String): String? = System.getenv(varName)
