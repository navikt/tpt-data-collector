package no.nav.metrics

import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

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

    private val checksTimer = Timer.builder("checks_runtime")
        .register(registry)

    fun webhookReceived() = webhookReceivedCounter.increment()

    fun checkFailed() = failedChecksCounter.increment()

    fun checksRanIn(duration: Duration) =
        checksTimer.record(duration.inWholeMilliseconds, TimeUnit.MILLISECONDS)
}