package no.nav.github

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertIs
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property

internal suspend fun withCapturedLogs(block: suspend (List<LogEvent>) -> Unit) {
    val context = assertIs<LoggerContext>(LogManager.getContext(false))
    val events = CopyOnWriteArrayList<LogEvent>()
    val appender = object : AbstractAppender("github-test-capture", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            events += event.toImmutable()
        }
    }
    val loggers = listOf(
        GitHubTransport::class.java.name,
        GithubWebhookHandler::class.java.name,
        "no.nav.checks.Checks",
        "no.nav.checks.githubapi.CriticalVulnerabilitiesCheck"
    ).map { context.getLogger(it) }
    appender.start()
    loggers.forEach { it.addAppender(appender) }
    try {
        block(events)
    } finally {
        loggers.forEach { it.removeAppender(appender) }
        appender.stop()
    }
}
