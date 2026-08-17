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

    private val webhookReceivedCounter: Counter by lazy {
        Counter.builder("webhooks_received")
            .register(registry)
    }

    private val failedChecksCounter: Counter by lazy {
        Counter.builder("webhooks_failed")
            .register(registry)
    }

    private val foundIssueCounter: Counter by lazy {
        Counter.builder("checks_issues_found")
            .register(registry)
    }

    private val msgsSentCounter: Counter by lazy {
        Counter.builder("msgs_sent_to_tpt")
            .register(registry)
    }


    fun webhookReceived() = webhookReceivedCounter.increment()

    fun checkFailed(n: Int = 1) = failedChecksCounter.increment(n.toDouble())

    fun issuesFound(n: Int = 1) = foundIssueCounter.increment(n.toDouble())

    fun msgsSentToTpt(n: Int = 1) = msgsSentCounter.increment(n.toDouble())

    fun checksRanIn(type: String, duration: Duration) =
        Timer.builder("checks_runtime")
            .tag("type", type)
            .register(registry)
            .record(duration.toJavaDuration())
}