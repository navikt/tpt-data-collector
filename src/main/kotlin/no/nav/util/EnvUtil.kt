package no.nav.util

    fun getEnvVar(
        varName: String,
        defaultValue: String? = null,
    ) = System.getenv(varName) ?: defaultValue ?: throw RuntimeException("Missing required variable $varName")
