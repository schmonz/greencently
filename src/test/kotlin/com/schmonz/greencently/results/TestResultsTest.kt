package com.schmonz.greencently.results

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestResultsTest {
    @Test
    fun `all green and complete is green`() {
        val results = TestResults(expectedCount = 5, actualCount = 5, greenCount = 5, redCount = 0)
        assertTrue(results.areCompleteAndGreen())
    }

    @Test
    fun `one red is not green`() {
        val results = TestResults(expectedCount = 5, actualCount = 5, greenCount = 4, redCount = 1)
        assertFalse(results.areCompleteAndGreen())
    }

    @Test
    fun `expected greater than actual (subset run) is not green`() {
        val results = TestResults(expectedCount = 5, actualCount = 3, greenCount = 3, redCount = 0)
        assertFalse(results.areCompleteAndGreen())
    }

    @Test
    fun `expected less than actual is not green`() {
        val results = TestResults(expectedCount = 3, actualCount = 5, greenCount = 5, redCount = 0)
        assertFalse(results.areCompleteAndGreen())
    }

    @Test
    fun `zero tests is not green`() {
        val results = TestResults(expectedCount = 0, actualCount = 0, greenCount = 0, redCount = 0)
        assertFalse(results.areCompleteAndGreen())
    }
}
