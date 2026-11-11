package com.m4lych.oblivion.logging

import android.content.Context
import com.m4lych.oblivion.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject

enum class LogLevel { DEBUG, INFO, WARNING, ERROR, CRITICAL }

class AppLogger(context: Context) {
    private val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val file = File(directory, "oblivion.log")
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    @Synchronized fun write(level: LogLevel, category: String, message: String, exception: Throwable? = null) {
        rotateIfNeeded()
        val stack = exception?.let { error -> StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString() }.orEmpty()
        val row = JSONObject()
            .put("timestamp", formatter.format(Date()))
            .put("level", level.name)
            .put("category", category.take(48))
            .put("component", category.take(48))
            .put("message", redact(message).take(1600))
            .put("exception_type", exception?.javaClass?.name.orEmpty())
            .put("exception_message", redact(exception?.message.orEmpty()).take(800))
            .put("stack_trace", redact(stack).take(8000))
            .put("correlation_id", UUID.randomUUID().toString())
            .put("app_version", BuildConfig.VERSION_NAME)
            .toString() + "\n"
        runCatching { file.appendText(row) }
    }
    fun read(): String = runCatching { file.takeIf(File::exists)?.readText()?.takeLast(48_000).orEmpty() }.getOrDefault("")
    fun exportFile(): File = file
    private fun rotateIfNeeded() {
        if (file.length() > 512 * 1024L) {
            File(directory, "oblivion.log.1").delete()
            file.renameTo(File(directory, "oblivion.log.1"))
        }
    }
    companion object {
        private val secretPatterns = listOf(
            Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+"),
            Regex("(?i)(access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|password|bot[_ -]?token)(\\s*[:=]\\s*)[^\\s,;]+"),
            Regex("(?i)eyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        )
        fun redact(input: String): String {
            var value = input
            secretPatterns.forEachIndexed { index, regex ->
                value = if (index == 2) regex.replace(value, "[REDACTED_TOKEN]") else regex.replace(value) { "${it.groupValues[1]}${it.groupValues.getOrElse(2) { "" }}[REDACTED]" }
            }
            return value
        }
    }
}
