package com.jmez11.deadeasyplayer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DurationFormatterTest(
    private val millis: Long,
    private val expectedOutput: String
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: formatDuration({0}ms) = \"{1}\"")
        fun data(): Collection<Array<Any>> = listOf(
            // Zero and negative durations
            arrayOf(0L, "0:00"),
            arrayOf(-1L, "0:00"),
            arrayOf(-5000L, "0:00"),

            // Sub-minute durations
            arrayOf(500L, "0:00"),
            arrayOf(1000L, "0:01"),
            arrayOf(5000L, "0:05"),
            arrayOf(9000L, "0:09"),
            arrayOf(10000L, "0:10"),
            arrayOf(45000L, "0:45"),
            arrayOf(59000L, "0:59"),

            // Minute boundaries
            arrayOf(60000L, "1:00"),
            arrayOf(61000L, "1:01"),
            arrayOf(125000L, "2:05"),
            arrayOf(599000L, "9:59"),
            arrayOf(600000L, "10:00"),
            arrayOf(3599000L, "59:59"),

            // Hour boundaries
            arrayOf(3600000L, "1:00:00"),
            arrayOf(3601000L, "1:00:01"),
            arrayOf(3661000L, "1:01:01"),
            arrayOf(7325000L, "2:02:05"),
            arrayOf(36000000L, "10:00:00"),
            arrayOf(86400000L, "24:00:00")
        )
    }

    @Test
    fun testFormatDuration() {
        val actual = formatDuration(millis)
        assertEquals("Incorrect format for $millis ms", expectedOutput, actual)
    }
}
