package com.m4lych.oblivion.data

import com.m4lych.oblivion.domain.WorldArchiveValidator
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class LocalWorld(val id: String, val name: String, val file: File, val size: Long, val importedAt: Long)
sealed interface ImportWorldResult {
    data class Imported(val world: LocalWorld) : ImportWorldResult
    data object Duplicate : ImportWorldResult
    data class Rejected(val message: String) : ImportWorldResult
}

class WorldLibrary(private val directory: File) {
    fun list(): List<LocalWorld> = directory.listFiles { file -> file.isFile && file.extension == "mcworld" }
        .orEmpty().map { file ->
            val parts = file.nameWithoutExtension.split("__", limit = 2)
            LocalWorld(parts.first(), parts.getOrElse(1) { "Imported world" }, file, file.length(), file.lastModified())
        }.sortedByDescending { it.importedAt }

    fun import(displayName: String, source: InputStream): ImportWorldResult {
        if (!directory.exists() && !directory.mkdirs()) return ImportWorldResult.Rejected("Oblivion could not create its private library.")
        val temp = File(directory, ".${UUID.randomUUID()}.tmp")
        return try {
            var size = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { output ->
                source.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > WorldArchiveValidator.MAX_ARCHIVE_BYTES) return ImportWorldResult.Rejected("The file exceeds the 512 MiB import limit.")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (list().any { it.file.name.startsWith(hash.take(24)) }) return ImportWorldResult.Duplicate
            val validation = temp.inputStream().use { WorldArchiveValidator.validate("selected.mcworld", size, it) }
            if (!validation.valid) return ImportWorldResult.Rejected(validation.reason ?: "Invalid world archive.")
            val cleanName = displayName.substringBeforeLast('.', displayName).replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(48).ifBlank { "Imported world" }
            val target = File(directory, "${hash.take(24)}__${cleanName}.mcworld")
            if (!temp.renameTo(target)) return ImportWorldResult.Rejected("Oblivion could not save this world.")
            ImportWorldResult.Imported(LocalWorld(hash.take(24), cleanName, target, target.length(), target.lastModified()))
        } catch (_: Exception) {
            ImportWorldResult.Rejected("World import failed. Check the selected file and available storage.")
        } finally {
            temp.delete()
        }
    }

    fun remove(world: LocalWorld): Boolean = world.file.parentFile == directory && world.file.delete()
}
