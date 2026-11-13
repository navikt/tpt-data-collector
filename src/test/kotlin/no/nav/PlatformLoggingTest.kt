package no.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.runBlocking
import no.nav.github.withCapturedLogs
import no.nav.logging.logEvent
import org.apache.logging.log4j.CloseableThreadContext
import org.apache.logging.log4j.ThreadContext
import org.slf4j.event.Level

class PlatformLoggingTest {
    @Test
    fun `application context is serialized into JSON and restored after each event`() = runBlocking {
        val loggerName = "no.nav.StructuredLoggingTest"
        val context = LogManager.getContext(false) as LoggerContext
        val layout = requireNotNull(context.configuration.getAppender<Appender>("stdout_json").layout)
        val logger = KtorSimpleLogger(loggerName)
        withCapturedLogs(loggerName) { events ->
            CloseableThreadContext.put("repository", "outer").use {
                logger.logEvent(
                    Level.WARN, "Repository update not sent",
                    "repository" to "navikt/repo", "team" to "example", "attempt" to 3
                )
                assertEquals("outer", ThreadContext.get("repository"))
                assertNull(ThreadContext.get("team"))
                logger.info("Unrelated event")
            }

            val output = String(layout.toByteArray(events.first()), Charsets.UTF_8).trimEnd()
            assertEquals(1, output.lines().size)
            val event = Json.parseToJsonElement(output).jsonObject
            val fields = event.getValue("mdc").jsonObject
            assertEquals("navikt/repo", fields.getValue("repository").jsonPrimitive.content)
            assertEquals("example", fields.getValue("team").jsonPrimitive.content)
            assertEquals("3", fields.getValue("attempt").jsonPrimitive.content)
            assertFalse(event.containsKey("exception"))
            assertEquals("outer", events.last().contextData.getValue<String>("repository"))
            assertFalse(events.last().contextData.containsKey("team"))
        }
    }

    @Test
    fun `platform warnings and exceptions are written as one structured log event`() {
        val context = LogManager.getContext(false) as LoggerContext
        val layout = requireNotNull(context.configuration.getAppender<Appender>("stdout_json").layout)
        val events = mutableListOf<String>()
        val appender = object : AbstractAppender("platform-log-capture", null, layout, true, Property.EMPTY_ARRAY) {
            override fun append(event: LogEvent) {
                events.add(String(layout.toByteArray(event), Charsets.UTF_8))
            }
        }
        val loggerName = "no.nav.PlatformLoggingTest"
        val logger = context.getLogger(loggerName)
        appender.start()
        logger.addAppender(appender)
        try {
            System.getLogger(loggerName).log(
                System.Logger.Level.WARNING,
                "Transaction failed and will be retried",
                IllegalStateException("Database unavailable"),
            )

            assertEquals(1, events.size)
            val output = events.single().trimEnd()
            assertEquals(1, output.lines().size)
            val event = Json.parseToJsonElement(output).jsonObject
            assertEquals("WARN", event.getValue("level").jsonPrimitive.content)
            assertEquals(loggerName, event.getValue("logger_name").jsonPrimitive.content)
            assertEquals("Transaction failed and will be retried", event.getValue("message").jsonPrimitive.content)
            val exception = event.getValue("exception").jsonObject
            assertEquals("java.lang.IllegalStateException", exception.getValue("exception_class").jsonPrimitive.content)
            assertEquals("Database unavailable", exception.getValue("exception_message").jsonPrimitive.content)
            assertTrue(exception.getValue("stacktrace").jsonPrimitive.content.contains("IllegalStateException: Database unavailable"))
        } finally {
            logger.removeAppender(appender)
            appender.stop()
        }
    }
}
