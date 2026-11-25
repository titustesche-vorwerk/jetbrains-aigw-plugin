package dev.aigw.sandbox.sbx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SandboxTest {
    private val sample = """
[
  {
    "name": "ug-claude-myproject",
    "id": "8d6ca658-0c60-4592-8190-91ed27314ea3",
    "agent": "claude",
    "status": "stopped",
    "last_used_at": "2026-11-25T12:24:31.652618Z",
    "mounts": [
      "/Users/dev/IdeaProjects/myproject:/run/sandbox/source:ro",
      "/Users/dev/Library/Application Support/com.docker.sandboxes/sandboxes/agent-skills:/home/agent/.claude/skills:ro"
    ],
    "workspaces": [
      "/Users/dev/IdeaProjects/myproject"
    ],
    "cpus": 2,
    "memory": "12g",
    "memory_mib": 12288,
    "created_at": "2026-11-25T11:09:15Z"
  }
]
"""

    @Test
    fun parsesListJson() {
        val list = parseSbxList(sample)
        assertEquals(
            listOf(Sandbox("ug-claude-myproject", "claude", SandboxStatus.STOPPED, listOf("/Users/dev/IdeaProjects/myproject"))),
            list,
        )
    }

    @Test
    fun mapsStatuses() {
        fun st(s: String) = parseSbxList("""[{"name":"a","agent":"x","status":"$s"}]""").single().status
        assertEquals(SandboxStatus.RUNNING, st("running"))
        assertEquals(SandboxStatus.RUNNING, st("running (detached)"))
        assertEquals(SandboxStatus.OTHER, st("weird"))
        assertEquals(SandboxStatus.STOPPED, st("stopped"))
    }

    @Test
    fun emptyAndMissingOptionalFields() {
        assertTrue(parseSbxList("[]").isEmpty())
        assertEquals(emptyList<String>(), parseSbxList("""[{"name":"a","status":"running"}]""").single().workspaces)
    }

    @Test
    fun belongsToMatchesWorkspaceIgnoringTrailingSlash() {
        val s = Sandbox("a", "claude", SandboxStatus.RUNNING, listOf("/p/myproject"))
        assertTrue(s.belongsTo("/p/myproject/"))
        assertTrue(s.belongsTo("/p/myproject"))
        assertFalse(s.belongsTo("/p/plan"))
    }

    @Test
    fun sortsProjectSandboxesFirst() {
        val a = Sandbox("a", "c", SandboxStatus.RUNNING, listOf("/other"))
        val b = Sandbox("b", "c", SandboxStatus.RUNNING, listOf("/p"))
        val c = Sandbox("c", "c", SandboxStatus.RUNNING, listOf("/p"))
        val d = Sandbox("d", "c", SandboxStatus.RUNNING, emptyList())
        assertEquals(listOf(b, c, a, d), sortForProject(listOf(d, c, a, b), "/p"))
    }
}
