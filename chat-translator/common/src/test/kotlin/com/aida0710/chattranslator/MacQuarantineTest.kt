package com.aida0710.chattranslator

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacQuarantineTest {
    private fun attributesOf(file: Path): String {
        val xattr = ProcessBuilder("/usr/bin/xattr", file.toString()).redirectErrorStream(true).start()
        return xattr.inputStream.bufferedReader().use { it.readText() }
    }

    private fun assumeMac() = assumeTrue(HostPlatform.current.os == HostPlatform.Os.MAC, "xattr is only on macOS")

    @Test
    fun removesMarkFromNestedFilesOnMac() {
        assumeMac()
        val directory = Files.createTempDirectory("quarantine test")
        val file = Files.createDirectories(directory.resolve("bin")).resolve("llama-server")
        Files.writeString(file, "binary")
        val mark = ProcessBuilder("/usr/bin/xattr", "-w", "com.apple.quarantine", "0081;00000000;Safari;", file.toString()).start()
        assertEquals(0, mark.waitFor())
        assertTrue(attributesOf(file).contains("com.apple.quarantine"))

        assertTrue(MacQuarantine.removeFrom(directory))

        assertFalse(attributesOf(file).contains("com.apple.quarantine"))
    }

    @Test
    fun filesWithoutMarkAreNotAnErrorOnMac() {
        assumeMac()
        val directory = Files.createTempDirectory("quarantine test")
        Files.writeString(directory.resolve("llama-server"), "binary")

        assertTrue(MacQuarantine.removeFrom(directory))
    }

    @Test
    fun otherOsesDoNotRunXattr() {
        val missing = Path.of("does-not-exist")

        assertTrue(MacQuarantine.removeFrom(missing, HostPlatform(HostPlatform.Os.LINUX, arm = false)))
        assertTrue(MacQuarantine.removeFrom(missing, HostPlatform(HostPlatform.Os.WINDOWS, arm = false)))
    }
}
