package no.nav.metrics

import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlin.time.Duration
import kotlin.time.toJavaDuration

object TPTMetrics {
    private val collectorRegistry = PrometheusRegistry.defaultRegistry

    val registry =
        PrometheusMeterRegistry(
            PrometheusConfig.DEFAULT,
            collectorRegistry,
            Clock.SYSTEM,
        )

    private val webhookReceivedCounter = Counter.builder("webhooks_received")
        .register(registry)

    private val failedChecksCounter = Counter.builder("webhooks_failed")
        .register(registry)

    private val foundIssueCounter = Counter.builder("checks_issues_found")
        .register(registry)
    

    fun webhookReceived() = webhookReceivedCounter.increment()

    fun checkFailed(n: Int = 1) = failedChecksCounter.increment(n.toDouble())

    fun issuesFound(n: Int = 1) = foundIssueCounter.increment(n.toDouble())

    fun checksRanIn(type: String, duration: Duration) =
        Timer.builder("checks_runtime")
            .tag("type", type)
            .register(registry)
            .record(duration.toJavaDuration())
}