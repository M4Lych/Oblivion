package com.m4lych.oblivion.hostspike

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

class SpikeLogger(context: Context) {
    private val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val logFile = File(directory, "mcbe-host-spike.jsonl")

    @Synchronized
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        rotateIfNeeded()
        val row = JSONObject()
            .put("timestamp", Instant.now().toString())
            .put("level", "INFO")
            .put("component", "mcbe-host-spike")
            .put("event", name.take(80))
            .put("correlation_id", UUID.randomUUID().toString())
        fields.forEach { (key, value) -> row.put(key.take(64), value?.toString()?.take(300) ?: JSONObject.NULL) }
        runCatching { logFile.appendText(row.toString() + "\n") }
        Log.i(TAG, row.toString())
    }

    private fun rotateIfNeeded() {
        if (logFile.length() > MAX_LOG_BYTES) {
            val previous = File(directory, "mcbe-host-spike.jsonl.1")
            previous.delete()
            logFile.renameTo(previous)
        }
    }

    companion object {
        private const val TAG = "OblivionHostSpike"
        private const val MAX_LOG_BYTES = 256 * 1024L
    }
}
