package com.m4lych.oblivion.domain

import java.io.InputStream
import java.util.zip.ZipInputStream

data class ArchiveValidation(val valid: Boolean, val reason: String? = null)

object WorldArchiveValidator {
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private const val MAX_EXPANDED_BYTES = 1_500L * 1024 * 1024

    fun validate(name: String, byteCount: Long, input: InputStream): ArchiveValidation {
        if (!name.endsWith(".mcworld", ignoreCase = true)) return ArchiveValidation(false, "Choose a .mcworld file.")
        if (byteCount <= 0 || byteCount > MAX_ARCHIVE_BYTES) return ArchiveValidation(false, "The file is empty or exceeds the 512 MiB import limit.")
        var entries = 0
        var expanded = 0L
        var hasLevel = false
        return try {
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) return ArchiveValidation(false, "The archive contains too many entries.")
                    if (!isSafePath(entry.name)) return ArchiveValidation(false, "The archive contains an unsafe path.")
                    if (!entry.isDirectory && entry.name == "level.dat") hasLevel = true
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        expanded += read
                        if (expanded > MAX_EXPANDED_BYTES) return ArchiveValidation(false, "The expanded archive exceeds the safety limit.")
                    }
                    zip.closeEntry()
                }
            }
            if (!hasLevel) ArchiveValidation(false, "This archive does not contain a Bedrock world level.dat file.")
            else ArchiveValidation(true)
        } catch (_: Exception) {
            ArchiveValidation(false, "The file is not a valid world archive.")
        }
    }

    fun isSafePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || '\u0000' in path || ':' in path) return false
        val normalized = path.replace('\\', '/')
        return normalized.split('/').none { it == ".." || it == "." }
    }
}
