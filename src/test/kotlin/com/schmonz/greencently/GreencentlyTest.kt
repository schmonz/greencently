package com.schmonz.greencently

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GreencentlyTest {
    @TempDir
    lateinit var projectDir: Path

    private val testFilename = "test-status-junit5"
    private val statusFile get() = projectDir.resolve(".greencently").resolve(testFilename)
    private val gitignoreFile get() = projectDir.resolve(".greencently/.gitignore")
    private lateinit var originalUserDir: String

    @BeforeEach
    fun setUp() {
        originalUserDir = System.getProperty("user.dir")
        System.setProperty("user.dir", projectDir.toString())
    }

    @AfterEach
    fun cleanUp() {
        System.setProperty("user.dir", originalUserDir)
    }

    @Test
    fun `writeStatus true creates status file and gitignore`() {
        val greencently = Greencently(testFilename)
        greencently.writeStatus(true)

        assertTrue(Files.exists(statusFile))
        assertTrue(Files.exists(gitignoreFile))
        assertEquals("*", gitignoreFile.readText())
    }

    @Test
    fun `writeStatus false deletes status file`() {
        val greencently = Greencently(testFilename)
        greencently.writeStatus(true)
        assertTrue(Files.exists(statusFile))

        greencently.writeStatus(false)
        assertFalse(Files.exists(statusFile))
    }
}
