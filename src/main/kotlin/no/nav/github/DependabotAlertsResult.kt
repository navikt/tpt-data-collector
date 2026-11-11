package no.nav.github

sealed interface DependabotAlertsResult {
    data class Available(val hasOpenCriticalAlerts: Boolean) : DependabotAlertsResult
    data object Disabled : DependabotAlertsResult
}
