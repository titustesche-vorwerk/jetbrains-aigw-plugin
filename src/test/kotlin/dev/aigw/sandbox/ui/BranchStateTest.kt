package dev.aigw.sandbox.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BranchStateTest {
    @Test fun keepsSelectionWhenStillPresent() {
        val prev = BranchState(listOf("master", "fix/xy"), selected = "fix/xy")
        val next = reduceBranches(prev, listOf("master", "fix/xy", "new"), head = "master", local = "master")
        assertEquals(BranchState(listOf("master", "fix/xy", "new"), selected = "fix/xy"), next)
    }

    @Test fun selectionFallsBackWhenBranchGone() {
        val prev = BranchState(listOf("master", "fix/xy"), selected = "fix/xy", loading = true)
        val next = reduceBranches(prev, listOf("master"), head = "master", local = "feature")
        assertEquals("master", next.selected)
        assertFalse(next.loading)
    }

    @Test fun defaultsToLocalNameThenHead() {
        assertEquals("feature", reduceBranches(null, listOf("master", "feature"), head = "master", local = "feature").selected)
        assertEquals("master", reduceBranches(null, listOf("master", "other"), head = "master", local = "feature").selected)
        assertNull(reduceBranches(null, emptyList(), head = null, local = "feature").selected)
    }
}
