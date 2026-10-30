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
import org.junit.jupiter.api.Test

class PlatformLoggingTest {
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
