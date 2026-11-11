package com.m4lych.oblivion

import com.m4lych.oblivion.domain.WorldArchiveValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorldArchiveValidatorTest {
    @Test fun rejectsTraversalAndAbsoluteArchivePaths() {
        assertFalse(WorldArchiveValidator.isSafePath("../level.dat"))
        assertFalse(WorldArchiveValidator.isSafePath("world/../../escape"))
        assertFalse(WorldArchiveValidator.isSafePath("/absolute"))
        assertFalse(WorldArchiveValidator.isSafePath("C:/drive"))
        assertTrue(WorldArchiveValidator.isSafePath("db/000001.ldb"))
    }

    @Test fun acceptsSmallZipWithRootLevelDat() {
        val bytes = zip("level.dat" to byteArrayOf(1, 2, 3), "db/000001.ldb" to byteArrayOf(4))
        assertTrue(WorldArchiveValidator.validate("world.mcworld", bytes.size.toLong(), ByteArrayInputStream(bytes)).valid)
    }

    @Test fun rejectsZipWithoutWorldMetadata() {
        val bytes = zip("readme.txt" to byteArrayOf(1))
        assertFalse(WorldArchiveValidator.validate("world.mcworld", bytes.size.toLong(), ByteArrayInputStream(bytes)).valid)
    }

    @Test fun rejectsWrongExtensionAndOversizedInputBeforeParsing() {
        assertFalse(WorldArchiveValidator.validate("world.zip", 8, ByteArrayInputStream(byteArrayOf())).valid)
        assertFalse(WorldArchiveValidator.validate("world.mcworld", WorldArchiveValidator.MAX_ARCHIVE_BYTES + 1, ByteArrayInputStream(byteArrayOf())).valid)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip -> entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() } }
        buffer.toByteArray()
    }
}
