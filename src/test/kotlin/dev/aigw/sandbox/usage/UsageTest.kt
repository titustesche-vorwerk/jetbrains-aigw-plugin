package dev.aigw.sandbox.usage

import dev.aigw.sandbox.cli.CliResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UsageTest {
    private val na = "usage not available"

    @Test
    fun notAvailableOnFailure() = assertEquals(na, summarizeUsage(CliResult(1, "Spent \$1", "boom")))

    @Test
    fun notAvailableOnEmpty() {
        assertEquals(na, summarizeUsage(CliResult(0, "", "")))
        assertEquals(na, summarizeUsage(CliResult(0, "  \n\n", "")))
    }

    @Test
    fun picksDollarLine() =
        assertEquals(
            "Spent \$12.30 of \$50.00",
            summarizeUsage(CliResult(0, "╭─ Usage ─╮\n│ Spent \$12.30 of \$50.00 │\n╰──╯", "")),
        )

    @Test
    fun skipsBorderOnlyLines() {
        assertEquals("Usage", summarizeUsage(CliResult(0, "╭────╮\n│    │\n  Usage  \n╰────╯", "")))
        assertEquals(na, summarizeUsage(CliResult(0, "╭────╮\n│    │\n╰────╯", "")))
    }

    @Test
    fun stripsAnsi() =
        assertEquals("Spent \$1 of \$2", summarizeUsage(CliResult(0, "\u001B[1mSpent \$1 of \$2\u001B[0m", "")))
}
